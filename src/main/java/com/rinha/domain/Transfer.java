package com.rinha.domain;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        String payerId,
        String payeeId,
        long amount,
        String idempotencyKey,
        Status status,
        String failureReason,
        Instant createdAt
) {
    public enum Status {
        pending, completed, failed
    }
}
