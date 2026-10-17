package com.nettrack.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabasePersistenceTest {

    @TempDir
    Path tempDir;

    private ApplicationContextRunner createContextRunner(Path dbPath) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        SqlInitializationAutoConfiguration.class
                ))
                .withUserConfiguration(SQLiteDataSourceConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:sqlite:" + dbPath.toAbsolutePath().normalize(),
                        "spring.datasource.driver-class-name=org.sqlite.JDBC",
                        "spring.datasource.hikari.maximum-pool-size=1",
                        "spring.sql.init.mode=always",
                        "spring.sql.init.schema-locations=classpath:schema.sql"
                );
    }

    @Test
    @DisplayName("1. Fresh database initialization creates expected schema and identifies SQLite")
    void freshDatabaseInitializesSchema() {
        Path dbPath = tempDir.resolve("fresh.db");

        createContextRunner(dbPath).run(context -> {
            assertThat(context).hasNotFailed();
            DataSource dataSource = context.getBean(DataSource.class);

            try (Connection conn = dataSource.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                assertEquals("SQLite", meta.getDatabaseProductName());

                try (ResultSet tables = meta.getTables(null, null, "network_interval_usage", null)) {
                    assertTrue(tables.next(), "Table network_interval_usage must exist");
                }
            }
        });

        assertTrue(Files.exists(dbPath), "Database file must exist on disk");
    }

    @Test
    @DisplayName("2. Missing parent directories are created automatically before DataSource opens")
    void missingParentDirectoriesAreCreated() {
        Path dbPath = tempDir.resolve("nested").resolve("sub").resolve("dir").resolve("auto_created.db");

        createContextRunner(dbPath).run(context -> {
            assertThat(context).hasNotFailed();
            assertTrue(Files.exists(dbPath.getParent()), "Parent directory must be created");
            assertTrue(Files.exists(dbPath), "Database file must exist");
        });
    }

    @Test
    @DisplayName("3. Insert and read representative values including deltas above Integer.MAX_VALUE and zero deltas")
    void insertAndReadRepresentativeValues() {
        Path dbPath = tempDir.resolve("values.db");

        createContextRunner(dbPath).run(context -> {
            assertThat(context).hasNotFailed();
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);

            // Large 64-bit deltas (> Integer.MAX_VALUE = 2_147_483_647L)
            long largeRx = 5_000_000_000L;
            long largeTx = 3_000_000_000L;
            long epochMs1 = 1_776_412_800_000L;

            jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    "wlp3s0", largeRx, largeTx, 1.5, epochMs1
            );

            // Zero deltas
            long epochMs2 = 1_776_412_801_000L;
            jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    "wlp3s0", 0L, 0L, 1.0, epochMs2
            );

            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM network_interval_usage ORDER BY id ASC");
            assertEquals(2, rows.size());

            Map<String, Object> row1 = rows.get(0);
            assertEquals("wlp3s0", row1.get("interface_name"));
            assertEquals(largeRx, ((Number) row1.get("rx_bytes_delta")).longValue());
            assertEquals(largeTx, ((Number) row1.get("tx_bytes_delta")).longValue());
            assertEquals(1.5, ((Number) row1.get("duration_seconds")).doubleValue(), 1e-6);
            assertEquals(epochMs1, ((Number) row1.get("recorded_at_epoch_ms")).longValue());

            Map<String, Object> row2 = rows.get(1);
            assertEquals(0L, ((Number) row2.get("rx_bytes_delta")).longValue());
            assertEquals(0L, ((Number) row2.get("tx_bytes_delta")).longValue());
            assertEquals(1.0, ((Number) row2.get("duration_seconds")).doubleValue(), 1e-6);
            assertEquals(epochMs2, ((Number) row2.get("recorded_at_epoch_ms")).longValue());
        });
    }

    @Test
    @DisplayName("4. Closing and reopening the context retains rows and CREATE TABLE IF NOT EXISTS preserves existing data")
    void closingAndReopeningRetainsRows() {
        Path dbPath = tempDir.resolve("reopen.db");

        // First application context lifecycle: insert row
        createContextRunner(dbPath).run(context -> {
            assertThat(context).hasNotFailed();
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    "wlp3s0", 4000L, 1000L, 1.0, 1_776_412_800_000L
            );
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM network_interval_usage", Integer.class);
            assertEquals(1, count);
        });

        // Second application context lifecycle: reopen same database file and re-run schema.sql
        createContextRunner(dbPath).run(context -> {
            assertThat(context).hasNotFailed();
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM network_interval_usage", Integer.class);
            assertEquals(1, count, "Existing rows must be retained across context reopen and schema reinitialization");

            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM network_interval_usage WHERE id = 1");
            assertEquals(4000L, ((Number) row.get("rx_bytes_delta")).longValue());
            assertEquals(1000L, ((Number) row.get("tx_bytes_delta")).longValue());
        });
    }

    @Test
    @DisplayName("5. Schema constraints reject negative deltas, non-positive duration, invalid interface, and nulls")
    void schemaConstraintsRejectInvalidData() {
        Path dbPath = tempDir.resolve("constraints.db");

        createContextRunner(dbPath).run(context -> {
            assertThat(context).hasNotFailed();
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);

            // Negative rx_bytes_delta
            assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    "wlp3s0", -1L, 100L, 1.0, 1000L
            ));

            // Negative tx_bytes_delta
            assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    "wlp3s0", 100L, -1L, 1.0, 1000L
            ));

            // Zero duration
            assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    "wlp3s0", 100L, 100L, 0.0, 1000L
            ));

            // Negative duration
            assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    "wlp3s0", 100L, 100L, -1.0, 1000L
            ));

            // Unsupported interface
            assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    "eth0", 100L, 100L, 1.0, 1000L
            ));

            // Null interface_name
            assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    null, 100L, 100L, 1.0, 1000L
            ));

            // Null recorded_at_epoch_ms
            assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                    "INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    "wlp3s0", 100L, 100L, 1.0, null
            ));
        });
    }

    @Test
    @DisplayName("6. Epoch-millisecond timestamps sort chronologically with integer ordering")
    void epochMillisecondsSortNumerically() {
        Path dbPath = tempDir.resolve("timestamps.db");

        createContextRunner(dbPath).run(context -> {
            assertThat(context).hasNotFailed();
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);

            jdbcTemplate.update("INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES ('wlp3s0', 10, 10, 1.0, 3000)");
            jdbcTemplate.update("INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES ('wlp3s0', 10, 10, 1.0, 1000)");
            jdbcTemplate.update("INSERT INTO network_interval_usage (interface_name, rx_bytes_delta, tx_bytes_delta, duration_seconds, recorded_at_epoch_ms) VALUES ('wlp3s0', 10, 10, 1.0, 2000)");

            List<Long> ordered = jdbcTemplate.queryForList("SELECT recorded_at_epoch_ms FROM network_interval_usage ORDER BY recorded_at_epoch_ms ASC", Long.class);
            assertEquals(List.of(1000L, 2000L, 3000L), ordered);
        });
    }

    @Test
    @DisplayName("7. In-memory and unsupported URL forms are rejected")
    void inMemoryAndUnsupportedUrlsAreRejected() {
        assertThrows(IllegalStateException.class, () -> SQLiteDataSourceConfig.validateAndPrepareDatabaseFile("jdbc:sqlite::memory:"));
        assertThrows(IllegalStateException.class, () -> SQLiteDataSourceConfig.validateAndPrepareDatabaseFile("jdbc:sqlite:"));
        assertThrows(IllegalStateException.class, () -> SQLiteDataSourceConfig.validateAndPrepareDatabaseFile("jdbc:sqlite:   "));
        assertThrows(IllegalStateException.class, () -> SQLiteDataSourceConfig.validateAndPrepareDatabaseFile("jdbc:sqlite:data/nettrack.db?mode=ro"));
        assertThrows(IllegalStateException.class, () -> SQLiteDataSourceConfig.validateAndPrepareDatabaseFile("jdbc:sqlite:file:data/nettrack.db"));
        assertThrows(IllegalStateException.class, () -> SQLiteDataSourceConfig.validateAndPrepareDatabaseFile("jdbc:h2:mem:test"));
    }

    @Test
    @DisplayName("8. An unusable path where parent directory is a regular file fails startup clearly")
    void unusablePathFailsStartupClearly() throws IOException {
        Path regularFileAsParent = tempDir.resolve("blocked_parent_file");
        Files.writeString(regularFileAsParent, "I am a regular file, not a directory");

        Path invalidDbPath = regularFileAsParent.resolve("cannot_create_here.db");
        String badUrl = "jdbc:sqlite:" + invalidDbPath.toAbsolutePath().normalize();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SQLiteDataSourceConfig.validateAndPrepareDatabaseFile(badUrl));
        assertTrue(ex.getMessage().contains("Failed to create parent directory for SQLite database at"));
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("9. Path identifying an existing directory instead of a file fails startup clearly")
    void existingDirectoryFailsStartupClearly() {
        Path existingDir = tempDir.resolve("just_a_dir");
        try {
            Files.createDirectories(existingDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String dirUrl = "jdbc:sqlite:" + existingDir.toAbsolutePath().normalize();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SQLiteDataSourceConfig.validateAndPrepareDatabaseFile(dirUrl));
        assertTrue(ex.getMessage().contains("Configured SQLite database path is an existing directory, not a database file"));
    }
}
