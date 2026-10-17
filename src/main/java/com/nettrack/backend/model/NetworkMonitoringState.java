package com.nettrack.backend.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of the current network monitoring state.
 *
 * @param status               the current monitoring status
 * @param rateResult           the latest network rate result, present only when status is READY
 * @param lastAttemptTimestamp the timestamp of the last completed attempt, null only when status is INITIAL
 */
public record NetworkMonitoringState(
        MonitoringStatus status,
        NetworkRateResult rateResult,
        Instant lastAttemptTimestamp
) {
    public NetworkMonitoringState {
        Objects.requireNonNull(status, "status must not be null");
        switch (status) {
            case INITIAL -> {
                if (rateResult != null || lastAttemptTimestamp != null) {
                    throw new IllegalArgumentException("INITIAL state requires null rateResult and null lastAttemptTimestamp");
                }
            }
            case BASELINE_ONLY -> {
                if (rateResult != null || lastAttemptTimestamp == null) {
                    throw new IllegalArgumentException("BASELINE_ONLY state requires null rateResult and non-null lastAttemptTimestamp");
                }
            }
            case READY -> {
                if (rateResult == null || lastAttemptTimestamp == null) {
                    throw new IllegalArgumentException("READY state requires non-null rateResult and non-null lastAttemptTimestamp");
                }
            }
            case ERROR -> {
                if (rateResult != null || lastAttemptTimestamp == null) {
                    throw new IllegalArgumentException("ERROR state requires null rateResult and non-null lastAttemptTimestamp");
                }
            }
        }
    }
}
