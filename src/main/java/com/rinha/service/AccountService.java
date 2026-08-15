package com.rinha.service;

import com.rinha.domain.Account;
import com.rinha.domain.ApiExceptions.ConflictException;
import com.rinha.domain.ApiExceptions.NotFoundException;
import com.rinha.domain.ApiExceptions.UnprocessableEntityException;
import com.rinha.domain.Transfer;
import com.rinha.repo.AccountRepository;
import com.rinha.repo.TransferRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;

    public AccountService(AccountRepository accounts, TransferRepository transfers) {
        this.accounts = accounts;
        this.transfers = transfers;
    }

    public Account create(String id, Long balance) {
        if (id == null || id.isBlank()) {
            throw new UnprocessableEntityException("id is required");
        }
        if (balance == null) {
            throw new UnprocessableEntityException("balance is required");
        }
        if (balance < 0) {
            throw new UnprocessableEntityException("balance must not be negative");
        }
        boolean created = accounts.insert(id, balance);
        if (!created) {
            throw new ConflictException("account already exists: " + id);
        }
        return new Account(id, balance);
    }

    public Account get(String id) {
        return accounts.findById(id)
                .orElseThrow(() -> new NotFoundException("account not found: " + id));
    }

    public record Statement(String accountId, long balance, List<Transfer> transfers) {
    }

    public Statement statement(String id) {
        Account account = get(id);
        List<Transfer> completed = transfers.findCompletedForAccount(id);
        return new Statement(account.id(), account.balance(), completed);
    }
}
