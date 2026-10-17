package com.nettrack.backend.service;

import com.nettrack.backend.model.MonitoringStatus;
import com.nettrack.backend.model.NetworkMonitoringState;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Thread-safe in-memory holder for the latest network monitoring state snapshot.
 */
@Service
public class NetworkMonitoringStateHolder {

    private volatile NetworkMonitoringState currentState =
            new NetworkMonitoringState(MonitoringStatus.INITIAL, null, null);

    /**
     * Returns the latest stored immutable snapshot of the monitoring state.
     * Does not trigger file reads, sampling, rate calculations, or baseline modifications.
     *
     * @return the current NetworkMonitoringState
     */
    public NetworkMonitoringState getCurrentState() {
        return currentState;
    }

    /**
     * Replaces the current state snapshot with a new immutable snapshot.
     *
     * @param newState the non-null state snapshot to publish
     */
    void updateState(NetworkMonitoringState newState) {
        this.currentState = Objects.requireNonNull(newState, "NetworkMonitoringState must not be null");
    }
}
