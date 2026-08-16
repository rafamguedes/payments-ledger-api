package com.payments.service;

import com.payments.domain.ApiExceptions.NotFoundException;
import com.payments.domain.ApiExceptions.UnprocessableEntityException;
import com.payments.domain.Transfer;
import com.payments.repo.TransferRepository;
import com.payments.worker.SettlementWorker;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TransferService {

    private final TransferRepository transfers;
    private final SettlementWorker worker;

    public TransferService(TransferRepository transfers, SettlementWorker worker) {
        this.transfers = transfers;
        this.worker = worker;
    }

    public record Outcome(Transfer transfer, boolean created) {
    }

    public Outcome create(String payerId, String payeeId, Long amount, String idempotencyKey) {
        validatePayload(payerId, payeeId, amount, idempotencyKey);

        // Idempotency wins over existence checks: replaying an already-known
        // key must always return the original. The insert handles that race
        // with ON CONFLICT; avoiding pre-check queries keeps the hot path to
        // one DB round trip for new transfers.
        TransferRepository.Result result;
        try {
            result = transfers.insertPendingOrGetExisting(payerId, payeeId, amount, idempotencyKey);
        } catch (DataIntegrityViolationException e) {
            throw new UnprocessableEntityException("payerId or payeeId does not exist");
        }

        if (result.created()) {
            worker.enqueue(result.transfer().id());
        }
        return new Outcome(result.transfer(), result.created());
    }

    public Transfer get(UUID id) {
        return transfers.findById(id)
                .orElseThrow(() -> new NotFoundException("transfer not found: " + id));
    }

    private void validatePayload(String payerId, String payeeId, Long amount, String idempotencyKey) {
        if (payerId == null || payerId.isBlank()) {
            throw new UnprocessableEntityException("payerId is required");
        }
        if (payeeId == null || payeeId.isBlank()) {
            throw new UnprocessableEntityException("payeeId is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new UnprocessableEntityException("idempotencyKey is required");
        }
        if (amount == null) {
            throw new UnprocessableEntityException("amount is required");
        }
        if (amount <= 0) {
            throw new UnprocessableEntityException("amount must be positive");
        }
        if (payerId.equals(payeeId)) {
            throw new UnprocessableEntityException("payerId and payeeId must differ");
        }
    }
}
