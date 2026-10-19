package com.nettrack.backend.service;

import com.nettrack.backend.model.NetworkInterfaceStats;
import com.nettrack.backend.model.NetworkRateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRateCalculatorServiceTest {

    private NetworkRateCalculatorService calculatorService;

    @BeforeEach
    void setUp() {
        calculatorService = new NetworkRateCalculatorService();
    }

    @Test
    @DisplayName("1. Baseline rate calculation with 2-second interval")
    void baselineCalculation() {
        NetworkInterfaceStats start = new NetworkInterfaceStats("wlp3s0", 1000L, 500L);
        NetworkInterfaceStats end = new NetworkInterfaceStats("wlp3s0", 5000L, 1500L);
        Duration duration = Duration.ofSeconds(2);

        NetworkRateResult result = calculatorService.calculateRate(start, end, duration);

        assertNotNull(result);
        assertEquals("wlp3s0", result.interfaceName());
        assertEquals(4000L, result.rxBytesDelta());
        assertEquals(1000L, result.txBytesDelta());
        assertEquals(2.0, result.durationSeconds(), 1e-9);
        assertEquals(2000.0, result.rxBytesPerSecond(), 1e-9);
        assertEquals(500.0, result.txBytesPerSecond(), 1e-9);
    }

    @Test
    @DisplayName("2. Fractional-second interval (500 ms)")
    void fractionalSecondInterval() {
        NetworkInterfaceStats start = new NetworkInterfaceStats("wlp3s0", 0L, 0L);
        NetworkInterfaceStats end = new NetworkInterfaceStats("wlp3s0", 1000L, 250L);
        Duration duration = Duration.ofMillis(500);

        NetworkRateResult result = calculatorService.calculateRate(start, end, duration);

        assertEquals(0.5, result.durationSeconds(), 1e-9);
        assertEquals(1000L, result.rxBytesDelta());
        assertEquals(250L, result.txBytesDelta());
        assertEquals(2000.0, result.rxBytesPerSecond(), 1e-9);
        assertEquals(500.0, result.txBytesPerSecond(), 1e-9);
    }

    @Test
    @DisplayName("3. Sub-millisecond precision (1 nanosecond)")
    void subMillisecondPrecision() {
        NetworkInterfaceStats start = new NetworkInterfaceStats("wlp3s0", 0L, 0L);
        NetworkInterfaceStats end = new NetworkInterfaceStats("wlp3s0", 1L, 0L);
        Duration duration = Duration.ofNanos(1);

        NetworkRateResult result = calculatorService.calculateRate(start, end, duration);

        assertEquals(1e-9, result.durationSeconds(), 1e-15);
        assertEquals(1L, result.rxBytesDelta());
        assertEquals(0L, result.txBytesDelta());
        assertEquals(1_000_000_000.0, result.rxBytesPerSecond(), 1.0);
        assertEquals(0.0, result.txBytesPerSecond(), 1e-9);
    }

    @Test
    @DisplayName("4. Distinct snapshot objects with identical counters produce zero deltas and 0.0 rates")
    void identicalCountersProduceZeroRates() {
        NetworkInterfaceStats start = new NetworkInterfaceStats("wlp3s0", 5000L, 2000L);
        NetworkInterfaceStats end = new NetworkInterfaceStats("wlp3s0", 5000L, 2000L);
        Duration duration = Duration.ofSeconds(1);

        NetworkRateResult result = calculatorService.calculateRate(start, end, duration);

        assertEquals(0L, result.rxBytesDelta());
        assertEquals(0L, result.txBytesDelta());
        assertEquals(0.0, result.rxBytesPerSecond(), 1e-9);
        assertEquals(0.0, result.txBytesPerSecond(), 1e-9);
    }

    @Test
    @DisplayName("5. Zero and negative durations are rejected")
    void zeroAndNegativeDurationsRejection() {
        NetworkInterfaceStats start = new NetworkInterfaceStats("wlp3s0", 1000L, 500L);
        NetworkInterfaceStats end = new NetworkInterfaceStats("wlp3s0", 2000L, 1000L);

        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(start, end, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> calculatorService.calculateRate(start, end, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> calculatorService.calculateRate(start, end, Duration.ofMillis(-500)));
    }

    @Test
    @DisplayName("6. Null arguments are rejected")
    void nullArgumentsRejection() {
        NetworkInterfaceStats valid = new NetworkInterfaceStats("wlp3s0", 1000L, 500L);
        Duration duration = Duration.ofSeconds(1);

        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(null, valid, duration));
        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(valid, null, duration));
        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(valid, valid, null));
    }

    @Test
    @DisplayName("7. Mismatched, non-wlp3s0, or null/blank interface names are rejected")
    void interfaceNameValidationRejection() {
        Duration duration = Duration.ofSeconds(1);

        // Mismatched interfaces
        NetworkInterfaceStats startWlp = new NetworkInterfaceStats("wlp3s0", 1000L, 500L);
        NetworkInterfaceStats endEth = new NetworkInterfaceStats("eth0", 2000L, 1000L);
        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(startWlp, endEth, duration));

        // Unsupported interface name (identical)
        NetworkInterfaceStats startEth = new NetworkInterfaceStats("eth0", 1000L, 500L);
        assertThrows(IllegalArgumentException.class,
                () -> calculatorService.calculateRate(startEth, startEth, duration));

        // Null interface name
        NetworkInterfaceStats startNull = new NetworkInterfaceStats(null, 1000L, 500L);
        NetworkInterfaceStats endNull = new NetworkInterfaceStats(null, 2000L, 1000L);
        assertThrows(IllegalArgumentException.class,
                () -> calculatorService.calculateRate(startNull, endNull, duration));

        // Blank interface name
        NetworkInterfaceStats startBlank = new NetworkInterfaceStats("   ", 1000L, 500L);
        NetworkInterfaceStats endBlank = new NetworkInterfaceStats("   ", 2000L, 1000L);
        assertThrows(IllegalArgumentException.class,
                () -> calculatorService.calculateRate(startBlank, endBlank, duration));
    }

    @Test
    @DisplayName("8. Negative values in each of the four counter positions are rejected")
    void negativeCountersRejection() {
        Duration duration = Duration.ofSeconds(1);

        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(
                new NetworkInterfaceStats("wlp3s0", -1L, 500L),
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L), duration));

        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(
                new NetworkInterfaceStats("wlp3s0", 1000L, -1L),
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L), duration));

        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L),
                new NetworkInterfaceStats("wlp3s0", -1L, 500L), duration));

        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L),
                new NetworkInterfaceStats("wlp3s0", 1000L, -1L), duration));
    }

    @Test
    @DisplayName("9. Counter decreases (RX, TX, or both) are rejected")
    void counterDecreaseRejection() {
        Duration duration = Duration.ofSeconds(1);

        // RX decrease
        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(
                new NetworkInterfaceStats("wlp3s0", 5000L, 500L),
                new NetworkInterfaceStats("wlp3s0", 4000L, 1000L), duration));

        // TX decrease
        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(
                new NetworkInterfaceStats("wlp3s0", 5000L, 1000L),
                new NetworkInterfaceStats("wlp3s0", 6000L, 500L), duration));

        // Both decrease
        assertThrows(IllegalArgumentException.class, () -> calculatorService.calculateRate(
                new NetworkInterfaceStats("wlp3s0", 5000L, 1000L),
                new NetworkInterfaceStats("wlp3s0", 4000L, 500L), duration));
    }

    @Test
    @DisplayName("10. Counters exceeding Integer.MAX_VALUE calculate correctly")
    void countersExceedingIntegerMax() {
        NetworkInterfaceStats start = new NetworkInterfaceStats("wlp3s0", 9_000_000_000L, 5_000_000_000L);
        NetworkInterfaceStats end = new NetworkInterfaceStats("wlp3s0", 11_000_000_000L, 8_000_000_000L);
        Duration duration = Duration.ofSeconds(2);

        NetworkRateResult result = calculatorService.calculateRate(start, end, duration);

        assertEquals(2_000_000_000L, result.rxBytesDelta());
        assertEquals(3_000_000_000L, result.txBytesDelta());
        assertEquals(1_000_000_000.0, result.rxBytesPerSecond(), 1e-9);
        assertEquals(1_500_000_000.0, result.txBytesPerSecond(), 1e-9);
    }

    @Test
    @DisplayName("11. Counters near Long.MAX_VALUE with small positive delta preserve accuracy")
    void countersNearLongMaxPreserveDelta() {
        long startRx = Long.MAX_VALUE - 100L;
        long endRx = Long.MAX_VALUE;
        NetworkInterfaceStats start = new NetworkInterfaceStats("wlp3s0", startRx, 1000L);
        NetworkInterfaceStats end = new NetworkInterfaceStats("wlp3s0", endRx, 2000L);
        Duration duration = Duration.ofSeconds(1);

        NetworkRateResult result = calculatorService.calculateRate(start, end, duration);

        assertEquals(100L, result.rxBytesDelta());
        assertEquals(1000L, result.txBytesDelta());
        assertEquals(100.0, result.rxBytesPerSecond(), 1e-9);
        assertEquals(1000.0, result.txBytesPerSecond(), 1e-9);
    }

    @Test
    @DisplayName("12. Large byte deltas calculate correctly without long nanosecond overflow")
    void largeDeltasWithoutNanosecondOverflow() {
        long largeRxDelta = 10_000_000_000_000_000L; // 10^16
        NetworkInterfaceStats start = new NetworkInterfaceStats("wlp3s0", 0L, 0L);
        NetworkInterfaceStats end = new NetworkInterfaceStats("wlp3s0", largeRxDelta, 0L);
        Duration duration = Duration.ofSeconds(2);

        NetworkRateResult result = calculatorService.calculateRate(start, end, duration);

        assertEquals(largeRxDelta, result.rxBytesDelta());
        assertEquals(5_000_000_000_000_000.0, result.rxBytesPerSecond(), 1.0);
    }

    @Test
    @DisplayName("13. Large positive Duration (too large for toNanos) produces finite positive rates")
    void largeDurationExceedingToNanos() {
        Duration largeDuration = Duration.ofSeconds(Long.MAX_VALUE);
        NetworkInterfaceStats start = new NetworkInterfaceStats("wlp3s0", 0L, 0L);
        NetworkInterfaceStats end = new NetworkInterfaceStats("wlp3s0", 1000L, 500L);

        NetworkRateResult result = calculatorService.calculateRate(start, end, largeDuration);

        assertTrue(result.durationSeconds() > 0.0);
        assertTrue(Double.isFinite(result.rxBytesPerSecond()));
        assertTrue(Double.isFinite(result.txBytesPerSecond()));
        assertTrue(result.rxBytesPerSecond() > 0.0);
        assertTrue(result.txBytesPerSecond() > 0.0);
    }
}
