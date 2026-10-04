package my.edu.ukm.ftsm.ecommerce.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * JDBC access to {@code order_outbox} (docs/phases/P2.md §6.5). Status 0 = NEW, 1 = SENT.
 * {@link #insert} runs outside any Spring transaction (autocommit): a successful return means
 * the purchase intent is durable, which is the precondition for answering 202.
 */
@Repository
public class OutboxDao {

    private static final Logger log = LoggerFactory.getLogger(OutboxDao.class);

    /** One NEW row claimed by {@link #lockBatch}. */
    public record OutboxRow(long id, String orderId, long eventId, String payload) {}

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public OutboxDao(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public void insert(String orderId, long userId, long eventId, String payload) {
        jdbc.update("INSERT INTO order_outbox(order_id,user_id,event_id,payload) VALUES(?,?,?,?)",
                orderId, userId, eventId, payload);
    }

    /**
     * Checks on a fresh pooled connection whether the row exists.
     *
     * @return TRUE / FALSE, or {@code null} when the check itself failed (cannot confirm).
     */
    public Boolean existsByOrderIdSafely(String orderId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM order_outbox WHERE order_id=?")) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("[SecKill] outbox existence check failed for orderId={}: {}", orderId, e.toString());
            return null;
        }
    }

    /** Claims up to {@code limit} NEW rows; must run inside a transaction so the row locks hold. */
    public List<OutboxRow> lockBatch(int limit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxDao.lockBatch must be called inside a transaction");
        }
        return jdbc.query(
                "SELECT id,order_id,event_id,payload FROM order_outbox WHERE status=0 ORDER BY id LIMIT ? "
                        + "FOR UPDATE SKIP LOCKED",
                (rs, i) -> new OutboxRow(rs.getLong("id"), rs.getString("order_id"),
                        rs.getLong("event_id"), rs.getString("payload")),
                limit);
    }

    public void markSent(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("UPDATE order_outbox SET status=1, sent_at=NOW(3) WHERE id=?",
                ids.stream().map(id -> new Object[]{id}).toList());
    }

    public long countPending() {
        return count("SELECT COUNT(*) FROM order_outbox WHERE status=0");
    }

    public long countPendingForEvent(long eventId) {
        return count("SELECT COUNT(*) FROM order_outbox WHERE event_id=? AND status=0", eventId);
    }

    public long countAllForEvent(long eventId) {
        return count("SELECT COUNT(*) FROM order_outbox WHERE event_id=?", eventId);
    }

    /** Deletes SENT rows older than {@code cutoff} whose event already has a final reconciliation verdict. */
    public int deleteSentReconciledBefore(Instant cutoff) {
        return jdbc.update("DELETE o FROM order_outbox o JOIN seckill_events e ON e.id=o.event_id "
                + "WHERE o.status=1 AND o.sent_at < ? AND e.reconciled=b'1'", Timestamp.from(cutoff));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
