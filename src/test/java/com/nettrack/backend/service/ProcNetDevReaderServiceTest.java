package com.nettrack.backend.service;

import com.nettrack.backend.model.NetworkInterfaceStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcNetDevReaderServiceTest {

    private ProcNetDevReaderService readerService;

    @BeforeEach
    void setUp() {
        readerService = new ProcNetDevReaderService();
    }

    @Test
    @DisplayName("Should correctly extract RX and TX bytes for wlp3s0 with distinct column values")
    void validWlp3s0Extraction() {
        List<String> lines = List.of(
                "Inter-|   Receive                                                |  Transmit",
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
                "wlp3s0: 4084760209 2846497    0    0    0     0          0         0 118464786 1428098    0   12    0     0       0          0"
        );

        NetworkInterfaceStats stats = readerService.parseLines(lines);

        assertNotNull(stats);
        assertEquals("wlp3s0", stats.interfaceName());
        assertEquals(4084760209L, stats.rxBytes());
        assertEquals(118464786L, stats.txBytes());
    }

    @Test
    @DisplayName("Should extract wlp3s0 counters when multiple interfaces are present including WARP and loopback")
    void multipleInterfacesInOutput() {
        List<String> lines = List.of(
                "Inter-|   Receive                                                |  Transmit",
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
                "    lo: 3557593   14883    0    0    0     0          0         0  3557593   14883    0    0    0     0       0          0",
                "enp2s0:       0       0    0    0    0     0          0         0        0       0    0    0    0     0       0          0",
                "  warp0: 99999999    1000    0    0    0     0          0         0 99999999    1000    0    0    0     0       0          0",
                "wlp3s0: 500000000 100000    0    0    0     0          0         0 200000000  50000    0    0    0     0       0          0",
                "docker0:      123      12    0    0    0     0          0         0      456      34    0    0    0     0       0          0"
        );

        NetworkInterfaceStats stats = readerService.parseLines(lines);

        assertEquals("wlp3s0", stats.interfaceName());
        assertEquals(500000000L, stats.rxBytes());
        assertEquals(200000000L, stats.txBytes());
    }

    @Test
    @DisplayName("Should require exact interface name matching and ignore prefix/suffix variants")
    void exactInterfaceMatchingOnly() {
        List<String> lines = List.of(
                "Inter-|   Receive                                                |  Transmit",
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
                "wlp3s0-extra: 100 10 0 0 0 0 0 0 200 20 0 0 0 0 0 0",
                "wlp3s01: 300 30 0 0 0 0 0 0 400 40 0 0 0 0 0 0",
                "mywlp3s0: 500 50 0 0 0 0 0 0 600 60 0 0 0 0 0 0"
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> readerService.parseLines(lines)
        );

        assertEquals("Target interface wlp3s0 not found in /proc/net/dev", exception.getMessage());
    }

    @Test
    @DisplayName("Should handle leading spaces, tabs, repeated spaces, and blank lines")
    void whitespaceAndFormattingVariations() {
        List<String> lines = List.of(
                "",
                "   \t  ",
                "Inter-|   Receive                                                |  Transmit",
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed",
                "\twlp3s0:\t123456\t\t10\t0\t0\t0\t0\t0\t0\t654321\t20\t0\t0\t0\t0\t0\t0  "
        );

        NetworkInterfaceStats stats = readerService.parseLines(lines);

        assertEquals("wlp3s0", stats.interfaceName());
        assertEquals(123456L, stats.rxBytes());
        assertEquals(654321L, stats.txBytes());
    }

    @Test
    @DisplayName("Should handle zero counters correctly")
    void zeroCounters() {
        List<String> lines = List.of(
                "wlp3s0: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
        );

        NetworkInterfaceStats stats = readerService.parseLines(lines);

        assertEquals("wlp3s0", stats.interfaceName());
        assertEquals(0L, stats.rxBytes());
        assertEquals(0L, stats.txBytes());
    }

    @Test
    @DisplayName("Should handle byte counters larger than Integer.MAX_VALUE")
    void largeCountersExceedingIntegerMax() {
        long largeRx = 9_000_000_000L;
        long largeTx = 50_000_000_000L;
        List<String> lines = List.of(
                "wlp3s0: " + largeRx + " 100 0 0 0 0 0 0 " + largeTx + " 200 0 0 0 0 0 0"
        );

        NetworkInterfaceStats stats = readerService.parseLines(lines);

        assertEquals("wlp3s0", stats.interfaceName());
        assertEquals(largeRx, stats.rxBytes());
        assertEquals(largeTx, stats.txBytes());
    }

    @Test
    @DisplayName("Should throw IllegalStateException when wlp3s0 interface is absent")
    void missingTargetInterfaceThrowsIllegalStateException() {
        List<String> emptyLines = Collections.emptyList();
        List<String> headersOnly = List.of(
                "Inter-|   Receive                                                |  Transmit",
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed"
        );

        assertThrows(IllegalStateException.class, () -> readerService.parseLines(emptyLines));
        assertThrows(IllegalStateException.class, () -> readerService.parseLines(headersOnly));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when row has incorrect field count")
    void wrongFieldCountThrowsIllegalArgumentException() {
        List<String> fifteenFields = List.of(
                "wlp3s0: 100 10 0 0 0 0 0 0 200 20 0 0 0 0 0"
        );
        List<String> seventeenFields = List.of(
                "wlp3s0: 100 10 0 0 0 0 0 0 200 20 0 0 0 0 0 0 0"
        );

        assertThrows(IllegalArgumentException.class, () -> readerService.parseLines(fifteenFields));
        assertThrows(IllegalArgumentException.class, () -> readerService.parseLines(seventeenFields));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when counters are nonnumeric")
    void nonNumericCountersThrowIllegalArgumentException() {
        List<String> invalidLines = List.of(
                "wlp3s0: abc 10 0 0 0 0 0 0 200 20 0 0 0 0 0 0"
        );

        assertThrows(IllegalArgumentException.class, () -> readerService.parseLines(invalidLines));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when counters are negative")
    void negativeCountersThrowIllegalArgumentException() {
        List<String> invalidLines = List.of(
                "wlp3s0: -100 10 0 0 0 0 0 0 200 20 0 0 0 0 0 0"
        );

        assertThrows(IllegalArgumentException.class, () -> readerService.parseLines(invalidLines));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when numeric overflow occurs beyond Long.MAX_VALUE")
    void numericOverflowThrowsIllegalArgumentException() {
        List<String> overflowLines = List.of(
                "wlp3s0: 99999999999999999999999999999 10 0 0 0 0 0 0 200 20 0 0 0 0 0 0"
        );

        assertThrows(IllegalArgumentException.class, () -> readerService.parseLines(overflowLines));
    }
}
