package com.nettrack.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration for SQLite DataSource ensuring parent directories are prepared
 * before DataSource construction and schema initialization.
 */
@Configuration(proxyBeanMethods = false)
public class SQLiteDataSourceConfig {

    public static final String SQLITE_PREFIX = "jdbc:sqlite:";

    /**
     * Constructs and configures the singleton HikariDataSource for SQLite.
     * Ensures parent directories are created before establishing database connectivity.
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        String rawUrl = properties.determineUrl();
        Path resolvedDbFile = validateAndPrepareDatabaseFile(rawUrl);

        HikariDataSource dataSource = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();

        dataSource.setJdbcUrl(SQLITE_PREFIX + resolvedDbFile.toString());
        dataSource.setDriverClassName(properties.determineDriverClassName());
        dataSource.setMaximumPoolSize(1);

        return dataSource;
    }

    /**
     * Validates that the URL represents a file-backed SQLite database and creates
     * any missing parent directories.
     *
     * @param rawUrl the raw JDBC URL
     * @return the resolved absolute Path to the SQLite database file
     * @throws IllegalStateException if URL format is invalid, in-memory, directory, or parent directory cannot be created
     */
    public static Path validateAndPrepareDatabaseFile(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IllegalStateException("Property 'spring.datasource.url' must not be blank");
        }

        String trimmed = rawUrl.trim();
        if (!trimmed.startsWith(SQLITE_PREFIX)) {
            throw new IllegalStateException("Unsupported DataSource URL format: '" + rawUrl
                    + "'. Only '" + SQLITE_PREFIX + "<file-path>' is supported.");
        }

        String pathPart = trimmed.substring(SQLITE_PREFIX.length()).trim();
        if (pathPart.isEmpty()) {
            throw new IllegalStateException("SQLite database path must not be empty in URL: '" + rawUrl + "'");
        }

        if (pathPart.equals(":memory:") || pathPart.startsWith(":memory:") || pathPart.contains("mode=memory")) {
            throw new IllegalStateException("In-memory SQLite databases (:memory:) are not supported: '" + rawUrl + "'");
        }

        if (pathPart.contains("?") || pathPart.startsWith("file:")) {
            throw new IllegalStateException("Unsupported SQLite URI or query form in URL: '" + rawUrl
                    + "'. Only direct file paths are supported.");
        }

        Path dbFile = Paths.get(pathPart).toAbsolutePath().normalize();
        if (Files.exists(dbFile) && Files.isDirectory(dbFile)) {
            throw new IllegalStateException("Configured SQLite database path is an existing directory, not a database file: " + dbFile);
        }

        Path parentDir = dbFile.getParent();
        if (parentDir != null) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create parent directory for SQLite database at: " + parentDir, e);
            }
        }

        return dbFile;
    }
}
