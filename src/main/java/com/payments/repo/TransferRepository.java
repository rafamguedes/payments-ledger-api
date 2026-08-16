package com.payments.repo;

import com.payments.domain.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private static final RowMapper<Transfer> MAPPER = (rs, rowNum) -> new Transfer(
            UUID.fromString(rs.getString("id")),
            rs.getString("payer_id"),
            rs.getString("payee_id"),
            rs.getLong("amount"),
            rs.getString("idempotency_key"),
            Transfer.Status.valueOf(rs.getString("status")),
            rs.getString("failure_reason"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new pending transfer unless the idempotency key already exists.
     * Returns the row that now owns that idempotency key: either the one just
     * inserted (created = true) or the pre-existing one (created = false).
     * The ON CONFLICT DO NOTHING makes this race-safe under N concurrent
     * requests carrying the same key — exactly one insert ever succeeds.
     */
    public Result insertPendingOrGetExisting(String payerId, String payeeId, long amount, String idempotencyKey) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        int inserted = jdbc.update(
                """
                INSERT INTO transfers (id, payer_id, payee_id, amount, idempotency_key, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'pending', ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                id, payerId, payeeId, amount, idempotencyKey, Timestamp.from(now));

        if (inserted == 1) {
            Transfer t = new Transfer(id, payerId, payeeId, amount, idempotencyKey,
                    Transfer.Status.pending, null, now);
            return new Result(t, true);
        }

        Transfer existing = findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency key conflicted but no row found: " + idempotencyKey));
        return new Result(existing, false);
    }

    public record Result(Transfer transfer, boolean created) {
    }

    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT * FROM transfers WHERE idempotency_key = ?", MAPPER, idempotencyKey)
                .stream().findFirst();
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc.query("SELECT * FROM transfers WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<Transfer> findCompletedForAccount(String accountId) {
        return jdbc.query(
                """
                SELECT * FROM transfers
                WHERE status = 'completed' AND (payer_id = ? OR payee_id = ?)
                ORDER BY created_at DESC
                """,
                MAPPER, accountId, accountId);
    }

    /** Fallback sweep: pending rows the in-memory queue might have missed (e.g. after a restart). */
    public List<UUID> findPendingIds(int limit) {
        return jdbc.query(
                "SELECT id FROM transfers WHERE status = 'pending' ORDER BY created_at ASC LIMIT ?",
                (rs, rowNum) -> UUID.fromString(rs.getString("id")), limit);
    }

    // --- used inside the worker's hand-managed transaction (same Connection) ---

    public Transfer lockForUpdate(Connection conn, UUID id) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM transfers WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Transfer(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("payer_id"),
                        rs.getString("payee_id"),
                        rs.getLong("amount"),
                        rs.getString("idempotency_key"),
                        Transfer.Status.valueOf(rs.getString("status")),
                        rs.getString("failure_reason"),
                        rs.getTimestamp("created_at").toInstant());
            }
        }
    }

    public void markCompleted(Connection conn, UUID id) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE transfers SET status = 'completed' WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    public void markFailed(Connection conn, UUID id, String reason) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE transfers SET status = 'failed', failure_reason = ? WHERE id = ?")) {
            ps.setString(1, reason);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }
}
