package com.nettrack.backend.repository;

import com.nettrack.backend.model.NetworkIntervalUsage;
import com.nettrack.backend.model.NetworkRateResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository for the {@code network_interval_usage} table.
 *
 * <p>Uses a constructor-injected {@link JdbcTemplate} and parameterized SQL.
 * The caller is responsible for supplying the timestamp associated with each
 * interval; this class never reads {@code NetworkMonitoringStateHolder} or
 * obtains its own timestamp.</p>
 *
 * <p>Derived rate fields ({@code rxBytesPerSecond}, {@code txBytesPerSecond}) are
 * not persisted. Only the five non-key columns defined in the schema are written.</p>
 *
 * <p>Two successful {@link #insert} calls always create two independent rows.
 * Identical timestamps are permitted. No deduplication, idempotency, or unique
 * timestamp constraint is applied at this stage.</p>
 */
@Repository
public class NetworkIntervalUsageRepository {

    private static final String INSERT_SQL =
            "INSERT INTO network_interval_usage " +
            "(interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) " +
            "VALUES (?, ?, ?, ?, ?)";

    private static final String FIND_BY_ID_SQL =
            "SELECT id, interface_name, rx_bytes_delta, tx_bytes_delta, " +
            "duration_seconds, recorded_at_epoch_ms " +
            "FROM network_interval_usage WHERE id = ?";

    private static final String REQUIRED_INTERFACE = "wlp3s0";

    private static final RowMapper<NetworkIntervalUsage> ROW_MAPPER = (rs, rowNum) ->
            new NetworkIntervalUsage(
                    rs.getLong("id"),
                    rs.getString("interface_name"),
                    rs.getLong("rx_bytes_delta"),
                    rs.getLong("tx_bytes_delta"),
                    rs.getDouble("duration_seconds"),
                    Instant.ofEpochMilli(rs.getLong("recorded_at_epoch_ms"))
            );

    private final JdbcTemplate jdbcTemplate;

    public NetworkIntervalUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts one row derived from the given rate result and caller-supplied timestamp.
     *
     * <p>Pre-insert validation (all violations throw {@link IllegalArgumentException}):</p>
     * <ul>
     *   <li>{@code result} must not be null.</li>
     *   <li>{@code recordedAt} must not be null.</li>
     *   <li>{@code result.interfaceName()} must be exactly {@value #REQUIRED_INTERFACE}.</li>
     *   <li>{@code result.rxBytesDelta()} must be &gt;= 0.</li>
     *   <li>{@code result.txBytesDelta()} must be &gt;= 0.</li>
     *   <li>{@code result.durationSeconds()} must be finite and strictly &gt; 0.</li>
     *   <li>{@code recordedAt} must be representable as an epoch-millisecond {@code long}.</li>
     * </ul>
     *
     * <p>The generated primary key is retrieved via {@link Statement#RETURN_GENERATED_KEYS}
     * on the same connection that executed the INSERT. This strategy is safe regardless
     * of connection-pool size because the key is obtained from the {@link PreparedStatement}
     * object, not from a subsequently acquired pooled connection.</p>
     *
     * <p>Database failures propagate as {@link org.springframework.dao.DataAccessException}.
     * This method never silently returns 0 or another success-like value after a failure.</p>
     *
     * @param result     the completed rate calculation; must not be null
     * @param recordedAt the instant associated with this interval; must not be null;
     *                   must be representable as epoch milliseconds within the {@code long} range
     * @return the generated primary key of the newly inserted row (always positive)
     * @throws IllegalArgumentException if any pre-insert validation check fails
     * @throws org.springframework.dao.DataAccessException on database failure
     */
    public long insert(NetworkRateResult result, Instant recordedAt) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        if (recordedAt == null) {
            throw new IllegalArgumentException("recordedAt must not be null");
        }
        if (!REQUIRED_INTERFACE.equals(result.interfaceName())) {
            throw new IllegalArgumentException(
                    "interfaceName must be '" + REQUIRED_INTERFACE + "', got: " + result.interfaceName());
        }
        if (result.rxBytesDelta() < 0) {
            throw new IllegalArgumentException(
                    "rxBytesDelta must be >= 0, got: " + result.rxBytesDelta());
        }
        if (result.txBytesDelta() < 0) {
            throw new IllegalArgumentException(
                    "txBytesDelta must be >= 0, got: " + result.txBytesDelta());
        }
        if (!Double.isFinite(result.durationSeconds()) || result.durationSeconds() <= 0.0) {
            throw new IllegalArgumentException(
                    "durationSeconds must be finite and > 0, got: " + result.durationSeconds());
        }

        final long epochMs;
        try {
            epochMs = recordedAt.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "recordedAt is outside the representable epoch-millisecond long range: " + recordedAt, e);
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, result.interfaceName());
            ps.setLong(2, result.rxBytesDelta());
            ps.setLong(3, result.txBytesDelta());
            ps.setDouble(4, result.durationSeconds());
            ps.setLong(5, epochMs);
            return ps;
        }, keyHolder);

        return keyHolder.getKey().longValue();
    }

    /**
     * Looks up one row by primary key.
     *
     * <p>Database failures propagate as {@link org.springframework.dao.DataAccessException}
     * and are never caught or converted to an empty result.</p>
     *
     * @param id the row identifier; must be strictly positive
     * @return the matching row wrapped in {@link Optional}, or {@link Optional#empty()}
     *         if no row with that id exists
     * @throws IllegalArgumentException if {@code id} is zero or negative
     * @throws org.springframework.dao.DataAccessException on database failure
     */
    public Optional<NetworkIntervalUsage> findById(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive, got: " + id);
        }
        List<NetworkIntervalUsage> results =
                jdbcTemplate.query(FIND_BY_ID_SQL, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
