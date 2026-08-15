package com.rinha.web;

import com.rinha.domain.Transfer;

import java.util.List;

public class Dtos {

    public record CreateAccountRequest(String id, Long balance) {
    }

    public record CreateTransferRequest(String payerId, String payeeId, Long amount, String idempotencyKey) {
    }

    public record StatementResponse(String accountId, long balance, List<Transfer> transfers) {
        public static StatementResponse from(String accountId, long balance, List<Transfer> transfers) {
            return new StatementResponse(accountId, balance, transfers);
        }
    }

    public record ErrorResponse(String error) {
    }

    private Dtos() {
    }
}
