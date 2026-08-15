package com.rinha.repo;

import com.rinha.domain.Account;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> MAPPER =
            (rs, rowNum) -> new Account(rs.getString("id"), rs.getLong("balance"));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false if the id already exists (caller should respond 409). */
    public boolean insert(String id, long balance) {
        try {
            jdbc.update("INSERT INTO accounts (id, balance) VALUES (?, ?)", id, balance);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    public Optional<Account> findById(String id) {
        return jdbc.query("SELECT id, balance FROM accounts WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public boolean exists(String id) {
        return !jdbc.queryForList("SELECT 1 FROM accounts WHERE id = ?", id).isEmpty();
    }

    /**
     * Locks the account row for update within the caller's transaction/connection.
     * Used by the settlement worker after acquiring locks in a globally consistent
     * order (by account id) to avoid deadlocks between concurrent transfers.
     */
    public long lockForUpdate(Connection conn, String id) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM accounts WHERE id = ? FOR UPDATE")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong("balance");
            }
        }
    }

    public void adjustBalance(Connection conn, String id, long delta) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
            ps.setLong(1, delta);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }
}
