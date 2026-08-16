package com.payments.worker;

import com.payments.config.PaymentsProperties;
import com.payments.domain.Transfer;
import com.payments.repo.AccountRepository;
import com.payments.repo.TransferRepository;
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
import java.util.concurrent.TimeUnit;
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

    private final BlockingQueue<UUID> queue = new LinkedBlockingQueue<>();
    // Guards against enqueueing (and thus attempting to settle) the same id twice.
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private ExecutorService consumers;

    public SettlementWorker(
            DataSource dataSource,
            AccountRepository accounts,
            TransferRepository transfers,
            PaymentsProperties properties
    ) {
        this.dataSource = dataSource;
        this.accounts = accounts;
        this.transfers = transfers;
        this.workerThreads = properties.getWorker().getThreads();
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
                    settle(id);
                } catch (Exception e) {
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

    private void settle(UUID id) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Transfer t = transfers.lockForUpdate(conn, id);
                if (t == null || t.status() != Transfer.Status.pending) {
                    // Already settled (or vanished) — nothing to do.
                    conn.rollback();
                    return;
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
                } else {
                    transfers.markFailed(conn, id, "insufficient_funds");
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }
}
