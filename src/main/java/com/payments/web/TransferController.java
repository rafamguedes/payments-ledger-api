package com.payments.web;

import com.payments.domain.Transfer;
import com.payments.service.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping("/transfers")
    public ResponseEntity<Transfer> create(@RequestBody Dtos.CreateTransferRequest body) {
        TransferService.Outcome outcome = service.create(
                body.payerId(), body.payeeId(), body.amount(), body.idempotencyKey());
        HttpStatus status = outcome.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.transfer());
    }

    @GetMapping("/transfers/{id}")
    public ResponseEntity<Transfer> get(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new com.payments.domain.ApiExceptions.NotFoundException("transfer not found: " + id);
        }
        return ResponseEntity.ok(service.get(uuid));
    }
}
