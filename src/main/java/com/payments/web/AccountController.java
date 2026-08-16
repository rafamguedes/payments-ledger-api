package com.payments.web;

import com.payments.domain.Account;
import com.payments.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping("/accounts")
    public ResponseEntity<Account> create(@RequestBody Dtos.CreateAccountRequest body) {
        Account account = service.create(body.id(), body.balance());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    @GetMapping("/accounts/{id}/statement")
    public ResponseEntity<Dtos.StatementResponse> statement(
            @PathVariable String id,
            @RequestParam(required = false) Integer limit
    ) {
        AccountService.Statement s = service.statement(id, limit);
        return ResponseEntity.ok(Dtos.StatementResponse.from(s.accountId(), s.balance(), s.transfers()));
    }
}
