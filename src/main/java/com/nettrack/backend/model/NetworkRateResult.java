package com.nettrack.backend.model;

/**
 * Immutable snapshot of calculated network transfer rate metrics for an interval.
 *
 * @param interfaceName    the network interface name (e.g., "wlp3s0")
 * @param rxBytesDelta     bytes received during the interval
 * @param txBytesDelta     bytes transmitted during the interval
 * @param rxBytesPerSecond average receive rate in bytes per second
 * @param txBytesPerSecond average transmit rate in bytes per second
 * @param durationSeconds  elapsed duration in fractional seconds
 */
public record NetworkRateResult(
    String interfaceName,
    long rxBytesDelta,
    long txBytesDelta,
    double rxBytesPerSecond,
    double txBytesPerSecond,
    double durationSeconds
) {
}
