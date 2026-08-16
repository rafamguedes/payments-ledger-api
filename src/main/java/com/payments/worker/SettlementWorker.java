package com.payments.worker;

import com.payments.config.PaymentsProperties;
import com.payments.domain.Transfer;
import com.payments.repo.AccountRepository;
import com.payments.repo.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes pending transfers and settles them: locks the payer and payee
 * account rows in a fixed global order (lower account id first) so that
 * concurrent transfers — including circular chains and fan-in patterns —
 * never deadlock against each other. The balance check happens only after
 * both locks are held, at the moment of settlement, exactly as the spec
 * requires.
 *
 * The transfer row itself is also locked (SELECT ... FOR UPDATE) and its
 * status re-checked before acting, so a transfer can never be settled twice
 * even if it somehow ends up enqueued more than once.
 */
@Component
public class SettlementWorker {

    private static final Logger log = LoggerFactory.getLogger(SettlementWorker.class);

    private final DataSource dataSource;
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final int workerThreads;
    private final Counter enqueuedTransfers;
    private final Counter duplicateEnqueueAttempts;
    private final Counter completedSettlements;
    private final Counter failedSettlements;
    private final Counter skippedSettlements;
    private final Counter settlementErrors;
    private final Timer settlementTimer;

    private final BlockingQueue<UUID> queue = new LinkedBlockingQueue<>();
    // Guards against enqueueing (and thus attempting to settle) the same id twice.
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private ExecutorService consumers;

    public SettlementWorker(
            DataSource dataSource,
            AccountRepository accounts,
            TransferRepository transfers,
            PaymentsProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.dataSource = dataSource;
        this.accounts = accounts;
        this.transfers = transfers;
        this.workerThreads = properties.getWorker().getThreads();
        this.enqueuedTransfers = Counter.builder("payments.settlement.enqueued")
                .description("Transfers accepted into the in-memory settlement queue")
                .register(meterRegistry);
        this.duplicateEnqueueAttempts = Counter.builder("payments.settlement.enqueue.duplicates")
                .description("Duplicate attempts to enqueue a transfer that is already in flight")
                .register(meterRegistry);
        this.completedSettlements = Counter.builder("payments.settlement.completed")
                .description("Transfers successfully settled")
                .register(meterRegistry);
        this.failedSettlements = Counter.builder("payments.settlement.failed")
                .description("Transfers settled as failed because business rules were not met")
                .register(meterRegistry);
        this.skippedSettlements = Counter.builder("payments.settlement.skipped")
                .description("Settlement attempts skipped because the transfer was no longer pending")
                .register(meterRegistry);
        this.settlementErrors = Counter.builder("payments.settlement.errors")
                .description("Unexpected settlement errors that leave a transfer pending for retry")
                .register(meterRegistry);
        this.settlementTimer = Timer.builder("payments.settlement.duration")
                .description("Time spent settling a transfer")
                .publishPercentileHistogram()
                .register(meterRegistry);
        Gauge.builder("payments.settlement.queue.size", queue, BlockingQueue::size)
                .description("Current number of transfer ids waiting in the settlement queue")
                .register(meterRegistry);
        Gauge.builder("payments.settlement.in_flight.size", inFlight, Set::size)
                .description("Current number of transfer ids being tracked by the worker")
                .register(meterRegistry);
        Gauge.builder("payments.settlement.worker.threads", this, worker -> worker.workerThreads)
                .description("Configured number of settlement worker consumers")
                .register(meterRegistry);
    }

    @PostConstruct
    public void start() {
        consumers = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < workerThreads; i++) {
            consumers.submit(this::consumeLoop);
        }
        // Pick up anything left pending from before this instance started.
        transfers.findPendingIds(10_000).forEach(this::enqueue);
        log.info("Settlement worker started with {} consumer threads", workerThreads);
    }

    @PreDestroy
    public void stop() {
        if (consumers != null) {
            consumers.shutdownNow();
        }
    }

    /** Called by the API right after a transfer is inserted as pending. */
    public void enqueue(UUID transferId) {
        if (inFlight.add(transferId)) {
            queue.offer(transferId);
            enqueuedTransfers.increment();
        } else {
            duplicateEnqueueAttempts.increment();
        }
    }

    /**
     * Fallback sweep: catches any pending transfer that, for whatever reason
     * (e.g. this instance restarted), isn't currently tracked in memory.
     */
    @Scheduled(fixedDelayString = "${payments.worker.sweep-interval-ms:500}")
    public void sweep() {
        for (UUID id : transfers.findPendingIds(500)) {
            enqueue(id);
        }
    }

    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                UUID id = queue.take();
                try {
                    SettlementResult result = settlementTimer.recordCallable(() -> settle(id));
                    record(result);
                } catch (Exception e) {
                    settlementErrors.increment();
                    log.error("Failed to settle transfer {}", id, e);
                    // Leave it pending; the sweep will retry it.
                } finally {
                    inFlight.remove(id);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void record(SettlementResult result) {
        switch (result) {
            case COMPLETED -> completedSettlements.increment();
            case FAILED -> failedSettlements.increment();
            case SKIPPED -> skippedSettlements.increment();
        }
    }

    private SettlementResult settle(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Transfer t = transfers.lockForUpdate(conn, id);
                if (t == null || t.status() != Transfer.Status.pending) {
                    // Already settled (or vanished) — nothing to do.
                    conn.rollback();
                    return SettlementResult.SKIPPED;
                }

                // Fixed global lock order prevents deadlocks between
                // concurrent/circular/fan-in transfers touching the same accounts.
                String first = t.payerId().compareTo(t.payeeId()) <= 0 ? t.payerId() : t.payeeId();
                String second = first.equals(t.payerId()) ? t.payeeId() : t.payerId();

                long firstBalance = accounts.lockForUpdate(conn, first);
                long secondBalance = second.equals(first) ? firstBalance : accounts.lockForUpdate(conn, second);

                long payerBalance = first.equals(t.payerId()) ? firstBalance : secondBalance;

                if (payerBalance >= t.amount()) {
                    accounts.adjustBalance(conn, t.payerId(), -t.amount());
                    accounts.adjustBalance(conn, t.payeeId(), t.amount());
                    transfers.markCompleted(conn, id);
                    conn.commit();
                    return SettlementResult.COMPLETED;
                } else {
                    transfers.markFailed(conn, id, "insufficient_funds");
                    conn.commit();
                    return SettlementResult.FAILED;
                }
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private enum SettlementResult {
        COMPLETED,
        FAILED,
        SKIPPED
    }
}
