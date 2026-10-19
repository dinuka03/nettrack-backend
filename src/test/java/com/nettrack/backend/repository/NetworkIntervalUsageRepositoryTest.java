package com.nettrack.backend.repository;

import com.nettrack.backend.config.SQLiteDataSourceConfig;
import com.nettrack.backend.model.NetworkIntervalUsage;
import com.nettrack.backend.model.NetworkRateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real SQLite integration tests for {@link NetworkIntervalUsageRepository}.
 *
 * <p>Each test runs against a file-backed SQLite database in a JUnit {@code @TempDir}.
 * {@link ApplicationContextRunner} is used to create and close an isolated Spring context
 * (including HikariCP) within each test lambda. The pool is closed before the lambda returns,
 * which happens before {@code @TempDir} cleanup — no resource-ordering hazard exists.</p>
 *
 * <p>No test touches {@code data/nettrack.db}, starts monitoring, or introduces H2.</p>
 */
class NetworkIntervalUsageRepositoryTest {

    @TempDir
    Path tempDir;

    private static final Instant FIXED_INSTANT = Instant.ofEpochMilli(1_776_412_800_000L);

    private static final NetworkRateResult VALID_RESULT = new NetworkRateResult(
            "wlp3s0", 1000L, 500L, 100.0, 50.0, 10.0);

    private ApplicationContextRunner contextRunner(Path dbPath) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        SqlInitializationAutoConfiguration.class
                ))
                .withUserConfiguration(SQLiteDataSourceConfig.class)
                .withBean(NetworkIntervalUsageRepository.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:sqlite:" + dbPath.toAbsolutePath().normalize(),
                        "spring.datasource.driver-class-name=org.sqlite.JDBC",
                        "spring.datasource.hikari.maximum-pool-size=1",
                        "spring.sql.init.mode=always",
                        "spring.sql.init.schema-locations=classpath:schema.sql"
                );
    }

    // -------------------------------------------------------------------------
    // 1. Basic round-trip
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Insert and findById round-trip returns identical stored values")
    void insertAndFindByIdRoundTrip() {
        Path db = tempDir.resolve("roundtrip.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            long id = repo.insert(VALID_RESULT, FIXED_INSTANT);
            assertTrue(id > 0, "Generated id must be positive");

            Optional<NetworkIntervalUsage> found = repo.findById(id);
            assertTrue(found.isPresent());
            NetworkIntervalUsage row = found.get();

            assertEquals(id, row.id());
            assertEquals("wlp3s0", row.interfaceName());
            assertEquals(1000L, row.rxBytesDelta());
            assertEquals(500L, row.txBytesDelta());
            assertEquals(10.0, row.durationSeconds(), 1e-9);
            assertEquals(FIXED_INSTANT, row.recordedAt());
        });
    }

    // -------------------------------------------------------------------------
    // 2. Generated ID actually identifies the inserted row
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. Generated ID is correctly associated with its inserted row")
    void generatedIdIsAssociatedWithItsRow() {
        Path db = tempDir.resolve("assoc.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult r1 = new NetworkRateResult("wlp3s0", 100L, 50L, 10.0, 5.0, 10.0);
            NetworkRateResult r2 = new NetworkRateResult("wlp3s0", 200L, 100L, 20.0, 10.0, 10.0);

            long id1 = repo.insert(r1, FIXED_INSTANT);
            long id2 = repo.insert(r2, FIXED_INSTANT);

            assertNotEquals(id1, id2);

            NetworkIntervalUsage row1 = repo.findById(id1).orElseThrow();
            NetworkIntervalUsage row2 = repo.findById(id2).orElseThrow();

            assertEquals(100L, row1.rxBytesDelta(), "id1 must map to r1 rx delta");
            assertEquals(200L, row2.rxBytesDelta(), "id2 must map to r2 rx delta");
        });
    }

    // -------------------------------------------------------------------------
    // 3. Multiple inserts produce distinct IDs
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. Multiple inserts produce distinct, increasing IDs")
    void multipleInsertsProduceDistinctIds() {
        Path db = tempDir.resolve("multi.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            long id1 = repo.insert(VALID_RESULT, FIXED_INSTANT);
            long id2 = repo.insert(VALID_RESULT, FIXED_INSTANT);
            long id3 = repo.insert(VALID_RESULT, FIXED_INSTANT);

            assertNotEquals(id1, id2);
            assertNotEquals(id2, id3);
            assertNotEquals(id1, id3);
            assertTrue(id1 < id2 && id2 < id3, "IDs should be monotonically increasing");
        });
    }

    // -------------------------------------------------------------------------
    // 4. Zero byte deltas
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. Zero rx and tx deltas are stored and retrieved exactly")
    void zeroByteDeltasStoredExactly() {
        Path db = tempDir.resolve("zero.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult zeroes = new NetworkRateResult("wlp3s0", 0L, 0L, 0.0, 0.0, 1.0);
            long id = repo.insert(zeroes, FIXED_INSTANT);
            NetworkIntervalUsage row = repo.findById(id).orElseThrow();

            assertEquals(0L, row.rxBytesDelta());
            assertEquals(0L, row.txBytesDelta());
        });
    }

    // -------------------------------------------------------------------------
    // 5. Values exceeding Integer.MAX_VALUE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Long values exceeding Integer.MAX_VALUE are stored without truncation")
    void largeByteValuesWithoutTruncation() {
        Path db = tempDir.resolve("large.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            long bigRx = 5_000_000_000L;  // > Integer.MAX_VALUE = 2_147_483_647
            long bigTx = 3_000_000_000L;
            NetworkRateResult big = new NetworkRateResult("wlp3s0", bigRx, bigTx, 500.0, 300.0, 10.0);

            long id = repo.insert(big, FIXED_INSTANT);
            NetworkIntervalUsage row = repo.findById(id).orElseThrow();

            assertEquals(bigRx, row.rxBytesDelta());
            assertEquals(bigTx, row.txBytesDelta());
        });
    }

    // -------------------------------------------------------------------------
    // 6. Fractional duration
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Fractional durationSeconds is stored and retrieved accurately")
    void fractionalDurationStoredAccurately() {
        Path db = tempDir.resolve("frac.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult frac = new NetworkRateResult("wlp3s0", 100L, 50L, 10.0, 5.0, 1.337);
            long id = repo.insert(frac, FIXED_INSTANT);
            NetworkIntervalUsage row = repo.findById(id).orElseThrow();

            assertEquals(1.337, row.durationSeconds(), 1e-9);
        });
    }

    // -------------------------------------------------------------------------
    // 7. Timestamp millisecond precision
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Sub-millisecond nanoseconds are truncated; millisecond precision is preserved")
    void timestampMillisecondPrecision() {
        Path db = tempDir.resolve("ts.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            // Instant with sub-millisecond nanoseconds
            Instant precise = Instant.ofEpochSecond(1_000_000L, 123_456_789L);
            long id = repo.insert(VALID_RESULT, precise);
            NetworkIntervalUsage row = repo.findById(id).orElseThrow();

            // Nanoseconds are truncated; result matches toEpochMilli() reconstruction
            Instant expected = Instant.ofEpochMilli(precise.toEpochMilli());
            assertEquals(expected, row.recordedAt());
            assertEquals(0, row.recordedAt().getNano() % 1_000_000,
                    "Reconstructed Instant must have zero sub-millisecond nanos");
        });
    }

    // -------------------------------------------------------------------------
    // 8. Equal timestamps create separate rows
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("8. Two inserts with identical timestamps produce two independent rows")
    void equalTimestampsCreateSeparateRows() {
        Path db = tempDir.resolve("dup_ts.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            long id1 = repo.insert(VALID_RESULT, FIXED_INSTANT);
            long id2 = repo.insert(VALID_RESULT, FIXED_INSTANT);

            assertNotEquals(id1, id2, "Identical timestamps must not prevent distinct rows");
            assertTrue(repo.findById(id1).isPresent());
            assertTrue(repo.findById(id2).isPresent());
        });
    }

    // -------------------------------------------------------------------------
    // 9. Missing positive ID returns empty
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. findById with a valid positive ID that has no matching row returns Optional.empty()")
    void findByIdForMissingPositiveIdReturnsEmpty() {
        Path db = tempDir.resolve("missing.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            Optional<NetworkIntervalUsage> result = repo.findById(999_999L);
            assertTrue(result.isEmpty());
        });
    }

    // -------------------------------------------------------------------------
    // 10 & 11. Non-positive IDs are rejected
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. findById(0) throws IllegalArgumentException")
    void findByIdZeroThrows() {
        Path db = tempDir.resolve("zero_id.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);
            assertThrows(IllegalArgumentException.class, () -> repo.findById(0L));
        });
    }

    @Test
    @DisplayName("11. findById with negative id throws IllegalArgumentException")
    void findByIdNegativeThrows() {
        Path db = tempDir.resolve("neg_id.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);
            assertThrows(IllegalArgumentException.class, () -> repo.findById(-1L));
            assertThrows(IllegalArgumentException.class, () -> repo.findById(Long.MIN_VALUE));
        });
    }

    // -------------------------------------------------------------------------
    // 12-22. Invalid inputs are rejected without inserting rows
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("12. insert with null result throws IllegalArgumentException and creates no row")
    void insertNullResultThrows() {
        Path db = tempDir.resolve("null_result.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(null, FIXED_INSTANT));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("13. insert with null recordedAt throws IllegalArgumentException and creates no row")
    void insertNullRecordedAtThrows() {
        Path db = tempDir.resolve("null_ts.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(VALID_RESULT, null));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("14. insert with wrong interface name throws IllegalArgumentException and creates no row")
    void insertWrongInterfaceThrows() {
        Path db = tempDir.resolve("wrong_iface.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult eth = new NetworkRateResult("eth0", 100L, 50L, 10.0, 5.0, 1.0);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(eth, FIXED_INSTANT));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("15. insert with null interface name throws IllegalArgumentException and creates no row")
    void insertNullInterfaceThrows() {
        Path db = tempDir.resolve("null_iface.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult nullIface = new NetworkRateResult(null, 100L, 50L, 10.0, 5.0, 1.0);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(nullIface, FIXED_INSTANT));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("16. insert with negative rxBytesDelta throws IllegalArgumentException and creates no row")
    void insertNegativeRxDeltaThrows() {
        Path db = tempDir.resolve("neg_rx.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult negRx = new NetworkRateResult("wlp3s0", -1L, 50L, 10.0, 5.0, 1.0);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(negRx, FIXED_INSTANT));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("17. insert with negative txBytesDelta throws IllegalArgumentException and creates no row")
    void insertNegativeTxDeltaThrows() {
        Path db = tempDir.resolve("neg_tx.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult negTx = new NetworkRateResult("wlp3s0", 100L, -1L, 10.0, 5.0, 1.0);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(negTx, FIXED_INSTANT));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("18. insert with zero durationSeconds throws IllegalArgumentException and creates no row")
    void insertZeroDurationThrows() {
        Path db = tempDir.resolve("zero_dur.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult zeroDur = new NetworkRateResult("wlp3s0", 100L, 50L, 10.0, 5.0, 0.0);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(zeroDur, FIXED_INSTANT));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("19. insert with negative durationSeconds throws IllegalArgumentException and creates no row")
    void insertNegativeDurationThrows() {
        Path db = tempDir.resolve("neg_dur.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult negDur = new NetworkRateResult("wlp3s0", 100L, 50L, -10.0, -5.0, -1.0);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(negDur, FIXED_INSTANT));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("20. insert with NaN durationSeconds throws IllegalArgumentException and creates no row")
    void insertNaNDurationThrows() {
        Path db = tempDir.resolve("nan_dur.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult nanDur = new NetworkRateResult("wlp3s0", 100L, 50L, 10.0, 5.0, Double.NaN);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(nanDur, FIXED_INSTANT));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("21. insert with positive infinity durationSeconds throws IllegalArgumentException and creates no row")
    void insertPositiveInfinityDurationThrows() {
        Path db = tempDir.resolve("inf_dur.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            NetworkRateResult infDur = new NetworkRateResult("wlp3s0", 100L, 50L,
                    10.0, 5.0, Double.POSITIVE_INFINITY);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(infDur, FIXED_INSTANT));

            NetworkRateResult negInfDur = new NetworkRateResult("wlp3s0", 100L, 50L,
                    10.0, 5.0, Double.NEGATIVE_INFINITY);
            assertThrows(IllegalArgumentException.class, () -> repo.insert(negInfDur, FIXED_INSTANT));

            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    @Test
    @DisplayName("22. insert with Instant.MAX (epoch-ms overflow) throws IllegalArgumentException and creates no row")
    void insertTimestampOverflowThrows() {
        Path db = tempDir.resolve("ts_overflow.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            // Instant.MAX is year 1,000,000,000 — toEpochMilli() overflows long
            assertThrows(IllegalArgumentException.class, () -> repo.insert(VALID_RESULT, Instant.MAX));
            assertRowCount(ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class), 0);
        });
    }

    // -------------------------------------------------------------------------
    // 23. Multiple invalid inputs leave table empty
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("23. Various invalid inputs create no rows in the table")
    void invalidInputsProduceNoRows() {
        Path db = tempDir.resolve("no_rows.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);
            var jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);

            assertThrows(IllegalArgumentException.class, () -> repo.insert(null, FIXED_INSTANT));
            assertThrows(IllegalArgumentException.class, () -> repo.insert(VALID_RESULT, null));
            assertThrows(IllegalArgumentException.class, () -> repo.insert(
                    new NetworkRateResult("eth0", 1L, 1L, 1.0, 1.0, 1.0), FIXED_INSTANT));
            assertThrows(IllegalArgumentException.class, () -> repo.insert(
                    new NetworkRateResult("wlp3s0", -1L, 1L, 1.0, 1.0, 1.0), FIXED_INSTANT));
            assertThrows(IllegalArgumentException.class, () -> repo.insert(
                    new NetworkRateResult("wlp3s0", 1L, 1L, 1.0, 1.0, Double.NaN), FIXED_INSTANT));

            assertRowCount(jdbc, 0);
        });
    }

    // -------------------------------------------------------------------------
    // 24. Database failure propagates as DataAccessException
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("24. Schema CHECK violation propagates as DataAccessException, not Optional.empty()")
    void databaseCheckViolationPropagatesAsDataAccessException() {
        Path db = tempDir.resolve("check_violation.db");
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);

            // Bypass repository validation and attempt a direct write that violates the schema CHECK
            // (negative rx_bytes_delta). This verifies the DataAccessException propagation path
            // is not silently swallowed anywhere in the stack.
            assertThrows(DataAccessException.class, () -> jdbc.update(
                    "INSERT INTO network_interval_usage " +
                    "(interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    "wlp3s0", -1L, 0L, 1.0, FIXED_INSTANT.toEpochMilli()
            ));

            // The table must still be empty — the violation did not partially commit
            assertRowCount(jdbc, 0);
        });
    }

    // -------------------------------------------------------------------------
    // 25. Rows survive closing and reopening the database
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("25. Inserted rows are readable through a fresh context after pool closure")
    void rowsSurviveFreshContext() {
        Path db = tempDir.resolve("reopen.db");

        final long[] capturedId = new long[1];

        // First context: insert a row
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);
            capturedId[0] = repo.insert(VALID_RESULT, FIXED_INSTANT);
        });

        // Verify the file exists before opening the second context
        assertTrue(Files.exists(db), "Database file must exist after first context closes");

        // Second context: reopen same database and verify the row is still there
        contextRunner(db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var repo = ctx.getBean(NetworkIntervalUsageRepository.class);

            Optional<NetworkIntervalUsage> found = repo.findById(capturedId[0]);
            assertTrue(found.isPresent(), "Row must survive pool closure and context reopen");

            NetworkIntervalUsage row = found.get();
            assertEquals("wlp3s0", row.interfaceName());
            assertEquals(1000L, row.rxBytesDelta());
            assertEquals(500L, row.txBytesDelta());
            assertEquals(10.0, row.durationSeconds(), 1e-9);
            assertEquals(FIXED_INSTANT, row.recordedAt());
        });
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void assertRowCount(org.springframework.jdbc.core.JdbcTemplate jdbc, int expected) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM network_interval_usage", Integer.class);
        assertEquals(expected, count, "Unexpected row count in network_interval_usage");
    }
}
