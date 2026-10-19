package com.nettrack.backend.model;

import java.time.Instant;

/**
 * Immutable read model representing one row in the {@code network_interval_usage} table.
 *
 * <p>Persisted timestamps have millisecond precision. The {@code recordedAt} field is
 * reconstructed from the stored {@code recorded_at_epoch_ms} integer column via
 * {@link Instant#ofEpochMilli(long)}. Any sub-millisecond information present on the
 * original {@link Instant} is discarded at write time and cannot be recovered from the
 * database. The nanosecond component of the reconstructed {@code recordedAt} is always
 * zero.</p>
 *
 * @param id              generated primary key assigned by SQLite
 * @param interfaceName   network interface name (always {@code "wlp3s0"} for the current schema)
 * @param rxBytesDelta    bytes received during the interval (non-negative)
 * @param txBytesDelta    bytes transmitted during the interval (non-negative)
 * @param durationSeconds elapsed duration in fractional seconds (positive and finite)
 * @param recordedAt      timestamp reconstructed from stored epoch milliseconds;
 *                        the nanosecond component is always zero
 */
public record NetworkIntervalUsage(
        long id,
        String interfaceName,
        long rxBytesDelta,
        long txBytesDelta,
        double durationSeconds,
        Instant recordedAt
) {
}
