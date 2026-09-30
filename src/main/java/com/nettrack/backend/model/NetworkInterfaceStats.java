package com.nettrack.backend.model;

/**
 * Immutable snapshot of network interface byte statistics.
 *
 * @param interfaceName the network interface name (e.g., "wlp3s0")
 * @param rxBytes       cumulative received bytes
 * @param txBytes       cumulative transmitted bytes
 */
public record NetworkInterfaceStats(
    String interfaceName,
    long rxBytes,
    long txBytes
) {
}
