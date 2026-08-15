package com.rinha.service;

import com.rinha.domain.ApiExceptions.NotFoundException;
import com.rinha.domain.ApiExceptions.UnprocessableEntityException;
import com.rinha.domain.Transfer;
import com.rinha.repo.AccountRepository;
import com.rinha.repo.TransferRepository;
import com.rinha.worker.SettlementWorker;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final SettlementWorker worker;

    public TransferService(AccountRepository accounts, TransferRepository transfers, SettlementWorker worker) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.worker = worker;
    }

    public record Outcome(Transfer transfer, boolean created) {
    }

    public Outcome create(String payerId, String payeeId, Long amount, String idempotencyKey) {
        validatePayload(payerId, payeeId, amount, idempotencyKey);

        // Idempotency wins over existence checks: replaying an already-known
        // key must always return the original, unchanged, regardless of what
        // else is in the body.
        var existing = transfers.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Outcome(existing.get(), false);
        }

        if (!accounts.exists(payerId)) {
            throw new UnprocessableEntityException("payerId does not exist: " + payerId);
        }
        if (!accounts.exists(payeeId)) {
            throw new UnprocessableEntityException("payeeId does not exist: " + payeeId);
        }

        TransferRepository.Result result =
                transfers.insertPendingOrGetExisting(payerId, payeeId, amount, idempotencyKey);

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
