package com.nettrack.backend.model;

/**
 * Status of the periodic network monitoring lifecycle.
 */
public enum MonitoringStatus {
    /**
     * Scheduling is disabled or no sampling attempt has completed yet.
     */
    INITIAL,

    /**
     * A baseline sample was established or re-established, but no rate is available yet.
     */
    BASELINE_ONLY,

    /**
     * A valid rate sample is available.
     */
    READY,

    /**
     * The last sampling attempt failed with an exception; no current rate is available.
     */
    ERROR
}
