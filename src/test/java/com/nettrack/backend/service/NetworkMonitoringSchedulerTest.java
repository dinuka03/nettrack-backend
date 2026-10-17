package com.nettrack.backend.service;

import com.nettrack.backend.model.MonitoringStatus;
import com.nettrack.backend.model.NetworkMonitoringState;
import com.nettrack.backend.model.NetworkRateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class NetworkMonitoringSchedulerTest {

    private StubSamplingService samplingService;
    private NetworkMonitoringStateHolder stateHolder;
    private MutableClock clock;
    private NetworkMonitoringScheduler scheduler;

    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-17T10:00:01Z");
    private static final Instant T2 = Instant.parse("2026-10-17T10:00:02Z");
    private static final Instant T3 = Instant.parse("2026-10-17T10:00:03Z");

    @BeforeEach
    void setUp() {
        samplingService = new StubSamplingService();
        stateHolder = new NetworkMonitoringStateHolder();
        clock = new MutableClock(T0);
        scheduler = new NetworkMonitoringScheduler(samplingService, stateHolder, clock);
    }

    @Test
    @DisplayName("1. Construction rejects null arguments and causes no sampling or clock reads")
    void constructionRejectsNullsAndCausesNoSideEffects() {
        assertThrows(NullPointerException.class, () -> new NetworkMonitoringScheduler(null, stateHolder, clock));
        assertThrows(NullPointerException.class, () -> new NetworkMonitoringScheduler(samplingService, null, clock));
        assertThrows(NullPointerException.class, () -> new NetworkMonitoringScheduler(samplingService, stateHolder, null));

        assertEquals(0, samplingService.sampleCallCount);
        assertEquals(0, clock.accessCount);
        assertEquals(MonitoringStatus.INITIAL, stateHolder.getCurrentState().status());
    }

    @Test
    @DisplayName("2. BASELINE_ONLY publication when sampleRate returns Optional.empty()")
    void baselineOnlyPublicationWhenSamplerReturnsEmpty() {
        clock.setInstant(T1);
        samplingService.queueBaselineOnly();

        scheduler.runPeriodicSample();

        NetworkMonitoringState state = stateHolder.getCurrentState();
        assertEquals(MonitoringStatus.BASELINE_ONLY, state.status());
        assertNull(state.rateResult());
        assertEquals(T1, state.lastAttemptTimestamp());
        assertEquals(1, samplingService.sampleCallCount);
    }

    @Test
    @DisplayName("3. READY publication for non-zero rates and zero rates")
    void readyPublicationForNonZeroAndZeroRates() {
        // Non-zero rate
        clock.setInstant(T1);
        NetworkRateResult normalRate = new NetworkRateResult("wlp3s0", 4000L, 1000L, 2000.0, 500.0, 2.0);
        samplingService.queueResult(normalRate);

        scheduler.runPeriodicSample();

        NetworkMonitoringState state1 = stateHolder.getCurrentState();
        assertEquals(MonitoringStatus.READY, state1.status());
        assertSame(normalRate, state1.rateResult());
        assertEquals(T1, state1.lastAttemptTimestamp());

        // Zero rate
        clock.setInstant(T2);
        NetworkRateResult zeroRate = new NetworkRateResult("wlp3s0", 0L, 0L, 0.0, 0.0, 1.0);
        samplingService.queueResult(zeroRate);

        scheduler.runPeriodicSample();

        NetworkMonitoringState state2 = stateHolder.getCurrentState();
        assertEquals(MonitoringStatus.READY, state2.status());
        assertSame(zeroRate, state2.rateResult());
        assertEquals(0.0, state2.rateResult().rxBytesPerSecond());
        assertEquals(0.0, state2.rateResult().txBytesPerSecond());
        assertEquals(T2, state2.lastAttemptTimestamp());
    }

    @Test
    @DisplayName("4. READY followed by Optional.empty() clears previous rate and publishes BASELINE_ONLY")
    void readyFollowedByEmptyClearsPreviousRate() {
        clock.setInstant(T1);
        NetworkRateResult rate = new NetworkRateResult("wlp3s0", 4000L, 1000L, 2000.0, 500.0, 2.0);
        samplingService.queueResult(rate);
        scheduler.runPeriodicSample();

        assertEquals(MonitoringStatus.READY, stateHolder.getCurrentState().status());
        assertNotNull(stateHolder.getCurrentState().rateResult());

        // Counter decrease or reset causes sampler to return empty
        clock.setInstant(T2);
        samplingService.queueBaselineOnly();
        scheduler.runPeriodicSample();

        NetworkMonitoringState state2 = stateHolder.getCurrentState();
        assertEquals(MonitoringStatus.BASELINE_ONLY, state2.status());
        assertNull(state2.rateResult(), "Old rate must be cleared, not retained");
        assertEquals(T2, state2.lastAttemptTimestamp());
    }

    @Test
    @DisplayName("5. Each documented exception publishes ERROR and clears previous rate")
    void documentedExceptionsPublishErrorAndClearRate() {
        NetworkRateResult rate = new NetworkRateResult("wlp3s0", 2000L, 500L, 2000.0, 500.0, 1.0);

        // Test IOException
        samplingService.queueResult(rate);
        clock.setInstant(T1);
        scheduler.runPeriodicSample();
        assertEquals(MonitoringStatus.READY, stateHolder.getCurrentState().status());

        samplingService.queueException(new IOException("Simulated I/O error"));
        clock.setInstant(T2);
        scheduler.runPeriodicSample();

        NetworkMonitoringState ioErrorState = stateHolder.getCurrentState();
        assertEquals(MonitoringStatus.ERROR, ioErrorState.status());
        assertNull(ioErrorState.rateResult());
        assertEquals(T2, ioErrorState.lastAttemptTimestamp());

        // Test IllegalStateException
        samplingService.queueResult(rate);
        clock.setInstant(T1);
        scheduler.runPeriodicSample();
        assertEquals(MonitoringStatus.READY, stateHolder.getCurrentState().status());

        samplingService.queueException(new IllegalStateException("Interface missing"));
        clock.setInstant(T2);
        scheduler.runPeriodicSample();

        NetworkMonitoringState stateErrorState = stateHolder.getCurrentState();
        assertEquals(MonitoringStatus.ERROR, stateErrorState.status());
        assertNull(stateErrorState.rateResult());
        assertEquals(T2, stateErrorState.lastAttemptTimestamp());

        // Test IllegalArgumentException
        samplingService.queueResult(rate);
        clock.setInstant(T1);
        scheduler.runPeriodicSample();
        assertEquals(MonitoringStatus.READY, stateHolder.getCurrentState().status());

        samplingService.queueException(new IllegalArgumentException("Corrupted stats row"));
        clock.setInstant(T2);
        scheduler.runPeriodicSample();

        NetworkMonitoringState argErrorState = stateHolder.getCurrentState();
        assertEquals(MonitoringStatus.ERROR, argErrorState.status());
        assertNull(argErrorState.rateResult());
        assertEquals(T2, argErrorState.lastAttemptTimestamp());
    }

    @Test
    @DisplayName("6. Repeated failures followed by successful recovery")
    void repeatedFailuresFollowedBySuccessfulRecovery() {
        clock.setInstant(T1);
        samplingService.queueException(new IOException("Err 1"));
        scheduler.runPeriodicSample();
        assertEquals(MonitoringStatus.ERROR, stateHolder.getCurrentState().status());

        clock.setInstant(T2);
        samplingService.queueException(new IOException("Err 2"));
        scheduler.runPeriodicSample();
        assertEquals(MonitoringStatus.ERROR, stateHolder.getCurrentState().status());

        // Recovery: 1st success establishes baseline
        clock.setInstant(T3);
        samplingService.queueBaselineOnly();
        scheduler.runPeriodicSample();
        assertEquals(MonitoringStatus.BASELINE_ONLY, stateHolder.getCurrentState().status());
        assertNull(stateHolder.getCurrentState().rateResult());
        assertEquals(T3, stateHolder.getCurrentState().lastAttemptTimestamp());

        // 2nd success calculates rate
        Instant t4 = Instant.parse("2026-10-17T10:00:04Z");
        clock.setInstant(t4);
        NetworkRateResult rate = new NetworkRateResult("wlp3s0", 1000L, 200L, 1000.0, 200.0, 1.0);
        samplingService.queueResult(rate);
        scheduler.runPeriodicSample();
        assertEquals(MonitoringStatus.READY, stateHolder.getCurrentState().status());
        assertSame(rate, stateHolder.getCurrentState().rateResult());
        assertEquals(t4, stateHolder.getCurrentState().lastAttemptTimestamp());
    }

    @Test
    @DisplayName("7. Timestamps accurately reflect completed attempt times")
    void timestampsReflectCompletedAttemptTimes() {
        clock.setInstant(T1);
        samplingService.queueBaselineOnly();
        scheduler.runPeriodicSample();
        assertEquals(T1, stateHolder.getCurrentState().lastAttemptTimestamp());

        clock.setInstant(T2);
        samplingService.queueResult(new NetworkRateResult("wlp3s0", 1000L, 500L, 1000.0, 500.0, 1.0));
        scheduler.runPeriodicSample();
        assertEquals(T2, stateHolder.getCurrentState().lastAttemptTimestamp());

        clock.setInstant(T3);
        samplingService.queueException(new IOException("Fail"));
        scheduler.runPeriodicSample();
        assertEquals(T3, stateHolder.getCurrentState().lastAttemptTimestamp());
    }

    @Test
    @DisplayName("8. Repeated getter calls do not invoke the sampler or clock")
    void repeatedGetterCallsDoNotInvokeSamplerOrClock() {
        clock.setInstant(T1);
        samplingService.queueBaselineOnly();
        scheduler.runPeriodicSample();

        int initialSampleCalls = samplingService.sampleCallCount;
        int initialClockAccess = clock.accessCount;

        for (int i = 0; i < 10; i++) {
            NetworkMonitoringState state = stateHolder.getCurrentState();
            assertNotNull(state);
        }

        assertEquals(initialSampleCalls, samplingService.sampleCallCount);
        assertEquals(initialClockAccess, clock.accessCount);
    }

    @Test
    @DisplayName("9. Publication replaces the whole immutable snapshot; previous snapshot remains unchanged")
    void publicationReplacesSnapshotImmutably() {
        clock.setInstant(T1);
        samplingService.queueBaselineOnly();
        scheduler.runPeriodicSample();

        NetworkMonitoringState snapshot1 = stateHolder.getCurrentState();
        assertEquals(MonitoringStatus.BASELINE_ONLY, snapshot1.status());

        clock.setInstant(T2);
        NetworkRateResult rate = new NetworkRateResult("wlp3s0", 1000L, 500L, 1000.0, 500.0, 1.0);
        samplingService.queueResult(rate);
        scheduler.runPeriodicSample();

        NetworkMonitoringState snapshot2 = stateHolder.getCurrentState();
        assertEquals(MonitoringStatus.READY, snapshot2.status());

        // Snapshot 1 remains unchanged
        assertEquals(MonitoringStatus.BASELINE_ONLY, snapshot1.status());
        assertNull(snapshot1.rateResult());
        assertEquals(T1, snapshot1.lastAttemptTimestamp());
    }

    @Test
    @DisplayName("10. Consecutive ERROR attempts log warning only once and recovery logs concise message")
    void consecutiveErrorsLogWarningOnlyOnce(CapturedOutput output) {
        samplingService.queueException(new IOException("Interface down"));
        samplingService.queueException(new IOException("Interface down"));
        samplingService.queueException(new IOException("Interface down"));

        clock.setInstant(T1);
        scheduler.runPeriodicSample(); // 1st failure -> logs warning

        clock.setInstant(T2);
        scheduler.runPeriodicSample(); // 2nd failure -> suppressed

        clock.setInstant(T3);
        scheduler.runPeriodicSample(); // 3rd failure -> suppressed

        String logs = output.getAll();
        int warningCount = countOccurrences(logs, "Network sampling failed: Interface down");
        assertEquals(1, warningCount, "Warning must be logged exactly once across consecutive failures");

        // Recover
        Instant t4 = Instant.parse("2026-10-17T10:00:04Z");
        clock.setInstant(t4);
        samplingService.queueBaselineOnly();
        scheduler.runPeriodicSample();

        String updatedLogs = output.getAll();
        assertTrue(updatedLogs.contains("Network sampling recovered successfully"));
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    // Deterministic test stub for NetworkSamplingService
    private static class StubSamplingService extends NetworkSamplingService {
        private final Deque<Object> outcomes = new ArrayDeque<>();
        int sampleCallCount = 0;

        StubSamplingService() {
            super(new ProcNetDevReaderService() {
                @Override
                public com.nettrack.backend.model.NetworkInterfaceStats readStats() {
                    return null;
                }
            }, new NetworkRateCalculatorService(), () -> 0L);
        }

        void queueResult(NetworkRateResult result) {
            outcomes.addLast(Optional.ofNullable(result));
        }

        void queueBaselineOnly() {
            outcomes.addLast(Optional.empty());
        }

        void queueException(Exception e) {
            outcomes.addLast(e);
        }

        @Override
        public synchronized Optional<NetworkRateResult> sampleRate() throws IOException {
            sampleCallCount++;
            if (outcomes.isEmpty()) {
                return Optional.empty();
            }
            Object item = outcomes.pollFirst();
            if (item instanceof IOException e) {
                throw e;
            } else if (item instanceof IllegalStateException e) {
                throw e;
            } else if (item instanceof IllegalArgumentException e) {
                throw e;
            } else if (item instanceof Optional<?> opt) {
                @SuppressWarnings("unchecked")
                Optional<NetworkRateResult> cast = (Optional<NetworkRateResult>) opt;
                return cast;
            }
            throw new IllegalStateException("Unexpected item in stub queue: " + item);
        }
    }

    // Deterministic test Clock
    private static class MutableClock extends Clock {
        private Instant currentInstant;
        int accessCount = 0;

        MutableClock(Instant initialInstant) {
            this.currentInstant = initialInstant;
        }

        void setInstant(Instant newInstant) {
            this.currentInstant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            accessCount++;
            return currentInstant;
        }
    }
}
