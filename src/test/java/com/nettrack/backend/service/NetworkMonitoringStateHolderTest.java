package com.nettrack.backend.service;

import com.nettrack.backend.model.MonitoringStatus;
import com.nettrack.backend.model.NetworkMonitoringState;
import com.nettrack.backend.model.NetworkRateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkMonitoringStateHolderTest {

    @Test
    @DisplayName("1. Initial state has INITIAL status, null rateResult, and null lastAttemptTimestamp")
    void initialStateIsInitialWithNulls() {
        NetworkMonitoringStateHolder holder = new NetworkMonitoringStateHolder();
        NetworkMonitoringState state = holder.getCurrentState();

        assertNotNull(state);
        assertEquals(MonitoringStatus.INITIAL, state.status());
        assertNull(state.rateResult());
        assertNull(state.lastAttemptTimestamp());
    }

    @Test
    @DisplayName("2. Repeated getter calls return stored snapshot without side effects")
    void repeatedGetterCallsReturnStoredSnapshot() {
        NetworkMonitoringStateHolder holder = new NetworkMonitoringStateHolder();
        NetworkMonitoringState state1 = holder.getCurrentState();
        NetworkMonitoringState state2 = holder.getCurrentState();

        assertSame(state1, state2);
    }

    @Test
    @DisplayName("3. updateState replaces the whole immutable snapshot and previous snapshot remains unchanged")
    void updateStateReplacesSnapshotImmutably() {
        NetworkMonitoringStateHolder holder = new NetworkMonitoringStateHolder();
        NetworkMonitoringState initialSnapshot = holder.getCurrentState();

        Instant t1 = Instant.parse("2026-10-17T12:00:00Z");
        NetworkMonitoringState baselineState = new NetworkMonitoringState(MonitoringStatus.BASELINE_ONLY, null, t1);
        holder.updateState(baselineState);

        NetworkMonitoringState publishedSnapshot1 = holder.getCurrentState();
        assertSame(baselineState, publishedSnapshot1);
        // Previous snapshot reference must remain completely unchanged
        assertEquals(MonitoringStatus.INITIAL, initialSnapshot.status());
        assertNull(initialSnapshot.rateResult());
        assertNull(initialSnapshot.lastAttemptTimestamp());

        // Update to READY
        Instant t2 = Instant.parse("2026-10-17T12:00:01Z");
        NetworkRateResult rate = new NetworkRateResult("wlp3s0", 1000L, 500L, 1000.0, 500.0, 1.0);
        NetworkMonitoringState readyState = new NetworkMonitoringState(MonitoringStatus.READY, rate, t2);
        holder.updateState(readyState);

        NetworkMonitoringState publishedSnapshot2 = holder.getCurrentState();
        assertSame(readyState, publishedSnapshot2);
        // Published snapshot 1 remains unchanged
        assertEquals(MonitoringStatus.BASELINE_ONLY, publishedSnapshot1.status());
        assertNull(publishedSnapshot1.rateResult());
        assertEquals(t1, publishedSnapshot1.lastAttemptTimestamp());
    }

    @Test
    @DisplayName("4. updateState rejects null state")
    void updateStateRejectsNull() {
        NetworkMonitoringStateHolder holder = new NetworkMonitoringStateHolder();
        assertThrows(NullPointerException.class, () -> holder.updateState(null));
    }

    @Test
    @DisplayName("5. NetworkMonitoringState enforces invariant rules for all statuses")
    void stateEnforcesInvariants() {
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        NetworkRateResult rate = new NetworkRateResult("wlp3s0", 1000L, 500L, 1000.0, 500.0, 1.0);

        // INITIAL invariants
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.INITIAL, rate, null));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.INITIAL, null, now));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.INITIAL, rate, now));

        // BASELINE_ONLY invariants
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.BASELINE_ONLY, null, null));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.BASELINE_ONLY, rate, now));

        // READY invariants
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.READY, null, now));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.READY, rate, null));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.READY, null, null));

        // ERROR invariants
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.ERROR, null, null));
        assertThrows(IllegalArgumentException.class, () -> new NetworkMonitoringState(MonitoringStatus.ERROR, rate, now));

        // Null status
        assertThrows(NullPointerException.class, () -> new NetworkMonitoringState(null, null, null));
    }
}
