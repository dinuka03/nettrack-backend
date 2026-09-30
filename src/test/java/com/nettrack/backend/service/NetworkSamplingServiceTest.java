package com.nettrack.backend.service;

import com.nettrack.backend.model.NetworkInterfaceStats;
import com.nettrack.backend.model.NetworkRateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSamplingServiceTest {

    private NetworkRateCalculatorService calculatorService;

    @BeforeEach
    void setUp() {
        calculatorService = new NetworkRateCalculatorService();
    }

    @Test
    @DisplayName("1. Construction causes no read or timestamp calls")
    void constructionCausesNoSideEffects() {
        AtomicInteger readCalls = new AtomicInteger(0);
        AtomicInteger timeCalls = new AtomicInteger(0);

        ProcNetDevReaderService stubReader = new ProcNetDevReaderService() {
            @Override
            public NetworkInterfaceStats readStats() {
                readCalls.incrementAndGet();
                return new NetworkInterfaceStats("wlp3s0", 1000L, 500L);
            }
        };

        LongSupplier timeSupplier = () -> {
            timeCalls.incrementAndGet();
            return 100L;
        };

        new NetworkSamplingService(stubReader, calculatorService, timeSupplier);

        assertEquals(0, readCalls.get());
        assertEquals(0, timeCalls.get());
    }

    @Test
    @DisplayName("2. First sample returns Optional.empty()")
    void firstSampleReturnsEmpty() throws IOException {
        StubReader reader = new StubReader(new NetworkInterfaceStats("wlp3s0", 1000L, 500L));
        TestTimeSupplier timeSupplier = new TestTimeSupplier(1_000_000_000L);

        NetworkSamplingService samplingService = new NetworkSamplingService(reader, calculatorService, timeSupplier);

        Optional<NetworkRateResult> result = samplingService.sampleRate();

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("3. Second sample produces correct deltas, rates, and duration")
    void secondSampleProducesRate() throws IOException {
        StubReader reader = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L),
                new NetworkInterfaceStats("wlp3s0", 5000L, 1500L)
        );
        TestTimeSupplier timeSupplier = new TestTimeSupplier(1_000_000_000L, 3_000_000_000L);

        NetworkSamplingService samplingService = new NetworkSamplingService(reader, calculatorService, timeSupplier);

        samplingService.sampleRate(); // 1st call -> baseline
        Optional<NetworkRateResult> result = samplingService.sampleRate(); // 2nd call -> 2s interval

        assertTrue(result.isPresent());
        NetworkRateResult rate = result.get();
        assertEquals("wlp3s0", rate.interfaceName());
        assertEquals(4000L, rate.rxBytesDelta());
        assertEquals(1000L, rate.txBytesDelta());
        assertEquals(2.0, rate.durationSeconds(), 1e-9);
        assertEquals(2000.0, rate.rxBytesPerSecond(), 1e-9);
        assertEquals(500.0, rate.txBytesPerSecond(), 1e-9);
    }

    @Test
    @DisplayName("4. Third sample uses the second sample as baseline, not the first")
    void thirdSampleUsesSecondAsBaseline() throws IOException {
        StubReader reader = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L),  // t = 1s
                new NetworkInterfaceStats("wlp3s0", 3000L, 1500L), // t = 2s (delta rx=2000)
                new NetworkInterfaceStats("wlp3s0", 6000L, 3500L)  // t = 3s (delta rx=3000)
        );
        TestTimeSupplier timeSupplier = new TestTimeSupplier(1_000_000_000L, 2_000_000_000L, 3_000_000_000L);

        NetworkSamplingService samplingService = new NetworkSamplingService(reader, calculatorService, timeSupplier);

        samplingService.sampleRate(); // 1st sample
        samplingService.sampleRate(); // 2nd sample
        Optional<NetworkRateResult> thirdResult = samplingService.sampleRate(); // 3rd sample

        assertTrue(thirdResult.isPresent());
        NetworkRateResult rate = thirdResult.get();
        assertEquals(3000L, rate.rxBytesDelta());
        assertEquals(2000L, rate.txBytesDelta());
        assertEquals(1.0, rate.durationSeconds(), 1e-9);
        assertEquals(3000.0, rate.rxBytesPerSecond(), 1e-9);
    }

    @Test
    @DisplayName("5. Unchanged counters produce a present zero-rate result")
    void unchangedCountersProduceZeroRate() throws IOException {
        StubReader reader = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 5000L, 2000L),
                new NetworkInterfaceStats("wlp3s0", 5000L, 2000L)
        );
        TestTimeSupplier timeSupplier = new TestTimeSupplier(1_000_000_000L, 2_000_000_000L);

        NetworkSamplingService samplingService = new NetworkSamplingService(reader, calculatorService, timeSupplier);

        samplingService.sampleRate();
        Optional<NetworkRateResult> result = samplingService.sampleRate();

        assertTrue(result.isPresent());
        NetworkRateResult rate = result.get();
        assertEquals(0L, rate.rxBytesDelta());
        assertEquals(0L, rate.txBytesDelta());
        assertEquals(0.0, rate.rxBytesPerSecond(), 1e-9);
        assertEquals(0.0, rate.txBytesPerSecond(), 1e-9);
    }

    @Test
    @DisplayName("6. RX decrease, TX decrease, and both decrease re-establish baseline and recover on next valid sample")
    void counterDecreaseReEstablishesBaseline() throws IOException {
        // RX decrease test
        StubReader readerRx = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 5000L, 1000L),  // t=1s (baseline)
                new NetworkInterfaceStats("wlp3s0", 4000L, 1200L),  // t=2s (RX decreased -> returns empty, re-establishes 4000/1200)
                new NetworkInterfaceStats("wlp3s0", 6000L, 1400L)   // t=3s (delta rx=2000, tx=200 over 1s)
        );
        TestTimeSupplier timeRx = new TestTimeSupplier(1_000_000_000L, 2_000_000_000L, 3_000_000_000L);

        NetworkSamplingService serviceRx = new NetworkSamplingService(readerRx, calculatorService, timeRx);

        serviceRx.sampleRate(); // sample 1
        Optional<NetworkRateResult> resultDecrease = serviceRx.sampleRate(); // sample 2 (decrease)
        assertTrue(resultDecrease.isEmpty());

        Optional<NetworkRateResult> resultRecovered = serviceRx.sampleRate(); // sample 3 (recovery)
        assertTrue(resultRecovered.isPresent());
        assertEquals(2000L, resultRecovered.get().rxBytesDelta());
        assertEquals(200L, resultRecovered.get().txBytesDelta());
    }

    @Test
    @DisplayName("7. IOException, IllegalStateException, and IllegalArgumentException from reader invalidate baseline and propagate")
    void readerExceptionsInvalidateBaselineAndPropagate() throws IOException {
        // Test IOException
        StubReader readerIo = new StubReader(new NetworkInterfaceStats("wlp3s0", 1000L, 500L));
        readerIo.queueException(new IOException("Disk error"));
        TestTimeSupplier timeSupplier = new TestTimeSupplier(1_000_000_000L, 2_000_000_000L);
        NetworkSamplingService serviceIo = new NetworkSamplingService(readerIo, calculatorService, timeSupplier);

        serviceIo.sampleRate(); // Baseline established
        assertThrows(IOException.class, serviceIo::sampleRate); // IOException thrown, baseline invalidated

        // Test IllegalStateException
        StubReader readerState = new StubReader(new NetworkInterfaceStats("wlp3s0", 1000L, 500L));
        readerState.queueException(new IllegalStateException("wlp3s0 missing"));
        NetworkSamplingService serviceState = new NetworkSamplingService(readerState, calculatorService, timeSupplier);

        serviceState.sampleRate();
        assertThrows(IllegalStateException.class, serviceState::sampleRate);

        // Test IllegalArgumentException
        StubReader readerArg = new StubReader(new NetworkInterfaceStats("wlp3s0", 1000L, 500L));
        readerArg.queueException(new IllegalArgumentException("Malformed row"));
        NetworkSamplingService serviceArg = new NetworkSamplingService(readerArg, calculatorService, timeSupplier);

        serviceArg.sampleRate();
        assertThrows(IllegalArgumentException.class, serviceArg::sampleRate);
    }

    @Test
    @DisplayName("8. After reader failure, the first successful call is baseline-only and second produces rate")
    void recoveryAfterReaderFailure() throws IOException {
        StubReader reader = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L) // Call 1 -> baseline
        );
        reader.queueException(new IOException("Transient error")); // Call 2 -> throws, invalidates baseline
        reader.queueStats(
                new NetworkInterfaceStats("wlp3s0", 2000L, 600L), // Call 3 -> new baseline
                new NetworkInterfaceStats("wlp3s0", 4000L, 800L)  // Call 4 -> rate calculation
        );

        TestTimeSupplier timeSupplier = new TestTimeSupplier(
                1_000_000_000L, 2_000_000_000L, 3_000_000_000L, 4_000_000_000L
        );

        NetworkSamplingService service = new NetworkSamplingService(reader, calculatorService, timeSupplier);

        assertTrue(service.sampleRate().isEmpty()); // Call 1
        assertThrows(IOException.class, service::sampleRate); // Call 2 (fails)
        assertTrue(service.sampleRate().isEmpty()); // Call 3 (re-establishes baseline)

        Optional<NetworkRateResult> rate = service.sampleRate(); // Call 4
        assertTrue(rate.isPresent());
        assertEquals(2000L, rate.get().rxBytesDelta());
        assertEquals(200L, rate.get().txBytesDelta());
    }

    @Test
    @DisplayName("9. Zero or negative elapsed time clears baseline and throws IllegalStateException; verifies recovery")
    void nonPositiveElapsedTimeClearsBaseline() throws IOException {
        StubReader reader = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L),  // t=2s
                new NetworkInterfaceStats("wlp3s0", 2000L, 600L),  // t=2s (0 ns elapsed -> throws, clears baseline)
                new NetworkInterfaceStats("wlp3s0", 3000L, 700L),  // t=3s (new baseline)
                new NetworkInterfaceStats("wlp3s0", 5000L, 900L)   // t=4s (rate calculation)
        );

        TestTimeSupplier timeSupplier = new TestTimeSupplier(
                2_000_000_000L, 2_000_000_000L, 3_000_000_000L, 4_000_000_000L
        );

        NetworkSamplingService service = new NetworkSamplingService(reader, calculatorService, timeSupplier);

        service.sampleRate(); // sample 1
        assertThrows(IllegalStateException.class, service::sampleRate); // sample 2 (0 ns elapsed)

        assertTrue(service.sampleRate().isEmpty()); // sample 3 (new baseline)
        Optional<NetworkRateResult> rate = service.sampleRate(); // sample 4
        assertTrue(rate.isPresent());
        assertEquals(2000L, rate.get().rxBytesDelta());
    }

    @Test
    @DisplayName("10. Negative absolute nanoTime values with positive elapsed difference work correctly")
    void negativeAbsoluteNanoTimeWithPositiveElapsedDifference() throws IOException {
        StubReader reader = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L),
                new NetworkInterfaceStats("wlp3s0", 3000L, 1500L)
        );

        // nanoTime can return negative values
        TestTimeSupplier timeSupplier = new TestTimeSupplier(-2_000_000_000L, -1_000_000_000L); // elapsed = 1s

        NetworkSamplingService service = new NetworkSamplingService(reader, calculatorService, timeSupplier);

        service.sampleRate();
        Optional<NetworkRateResult> result = service.sampleRate();

        assertTrue(result.isPresent());
        assertEquals(1.0, result.get().durationSeconds(), 1e-9);
        assertEquals(2000L, result.get().rxBytesDelta());
    }

    @Test
    @DisplayName("11. Signed long boundary rollover for nanoTime produces exact positive elapsed difference")
    void signedBoundaryTimingRollover() throws IOException {
        StubReader reader = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L),
                new NetworkInterfaceStats("wlp3s0", 2000L, 1000L)
        );

        long previous = Long.MAX_VALUE - 5;
        long current = Long.MIN_VALUE + 4; // Rollover across signed long boundary: current - previous = 10 ns

        TestTimeSupplier timeSupplier = new TestTimeSupplier(previous, current);

        NetworkSamplingService service = new NetworkSamplingService(reader, calculatorService, timeSupplier);

        service.sampleRate();
        Optional<NetworkRateResult> result = service.sampleRate();

        assertTrue(result.isPresent());
        assertEquals(1e-8, result.get().durationSeconds(), 1e-15); // 10 ns = 0.00000001 s
        assertEquals(1000L, result.get().rxBytesDelta());
    }

    @Test
    @DisplayName("12. Unexpected IllegalArgumentException from calculator propagates and invalidates baseline")
    void unexpectedCalculatorExceptionPropagatesAndInvalidatesBaseline() throws IOException {
        StubReader reader = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L),
                new NetworkInterfaceStats("wlp3s0", 2000L, 1000L),
                new NetworkInterfaceStats("wlp3s0", 3000L, 1500L),
                new NetworkInterfaceStats("wlp3s0", 5000L, 2000L)
        );

        NetworkRateCalculatorService faultyCalculator = new NetworkRateCalculatorService() {
            private int callCount = 0;

            @Override
            public NetworkRateResult calculateRate(NetworkInterfaceStats start, NetworkInterfaceStats end, Duration duration) {
                if (++callCount == 1) {
                    throw new IllegalArgumentException("Unexpected calculator fault");
                }
                return super.calculateRate(start, end, duration);
            }
        };

        TestTimeSupplier timeSupplier = new TestTimeSupplier(
                1_000_000_000L, 2_000_000_000L, 3_000_000_000L, 4_000_000_000L
        );

        NetworkSamplingService service = new NetworkSamplingService(reader, faultyCalculator, timeSupplier);

        service.sampleRate(); // 1st call -> baseline
        assertThrows(IllegalArgumentException.class, service::sampleRate); // 2nd call -> calculator throws, baseline cleared

        assertTrue(service.sampleRate().isEmpty()); // 3rd call -> fresh baseline
        Optional<NetworkRateResult> rate = service.sampleRate(); // 4th call -> succeeds
        assertTrue(rate.isPresent());
    }

    @Test
    @DisplayName("13. Separate service instances do not share baseline state")
    void separateInstancesDoNotShareState() throws IOException {
        StubReader reader1 = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 1000L, 500L),
                new NetworkInterfaceStats("wlp3s0", 3000L, 1500L)
        );
        StubReader reader2 = new StubReader(
                new NetworkInterfaceStats("wlp3s0", 10000L, 5000L)
        );

        TestTimeSupplier timeSupplier1 = new TestTimeSupplier(1_000_000_000L, 2_000_000_000L);
        TestTimeSupplier timeSupplier2 = new TestTimeSupplier(1_000_000_000L);

        NetworkSamplingService service1 = new NetworkSamplingService(reader1, calculatorService, timeSupplier1);
        NetworkSamplingService service2 = new NetworkSamplingService(reader2, calculatorService, timeSupplier2);

        service1.sampleRate(); // service1 baseline
        assertTrue(service2.sampleRate().isEmpty()); // service2 baseline independently empty

        Optional<NetworkRateResult> result1 = service1.sampleRate(); // service1 calculates rate
        assertTrue(result1.isPresent());
        assertEquals(2000L, result1.get().rxBytesDelta());
    }

    @Test
    @DisplayName("14. Concurrency test: overlapping calls serialize reads and baseline updates without interleaving")
    void concurrencyTestSerializesCalls() throws Exception {
        AtomicInteger readCounter = new AtomicInteger(0);
        CountDownLatch syncLatch = new CountDownLatch(1);

        ProcNetDevReaderService blockingReader = new ProcNetDevReaderService() {
            @Override
            public NetworkInterfaceStats readStats() {
                int count = readCounter.incrementAndGet();
                if (count == 1) {
                    try {
                        syncLatch.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new NetworkInterfaceStats("wlp3s0", 1000L, 500L);
                } else {
                    return new NetworkInterfaceStats("wlp3s0", 3000L, 1500L);
                }
            }
        };

        TestTimeSupplier timeSupplier = new TestTimeSupplier(1_000_000_000L, 3_000_000_000L);
        NetworkSamplingService service = new NetworkSamplingService(blockingReader, calculatorService, timeSupplier);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<NetworkRateResult>> future1 = executor.submit(service::sampleRate);
            // Give thread 1 time to enter readStats and block on latch
            Thread.sleep(50);
            Future<Optional<NetworkRateResult>> future2 = executor.submit(service::sampleRate);

            syncLatch.countDown(); // Release thread 1

            Optional<NetworkRateResult> res1 = future1.get(2, TimeUnit.SECONDS);
            Optional<NetworkRateResult> res2 = future2.get(2, TimeUnit.SECONDS);

            // One call must be the baseline (empty) and one must be the rate (present)
            assertTrue(res1.isEmpty() ^ res2.isEmpty());
            if (res1.isPresent()) {
                assertEquals(2000L, res1.get().rxBytesDelta());
            } else {
                assertEquals(2000L, res2.get().rxBytesDelta());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // Helper reader stub for queuing returned stats or exceptions
    private static class StubReader extends ProcNetDevReaderService {
        private final Deque<Object> queue = new ArrayDeque<>();

        StubReader(NetworkInterfaceStats... initialStats) {
            queueStats(initialStats);
        }

        void queueStats(NetworkInterfaceStats... stats) {
            for (NetworkInterfaceStats s : stats) {
                queue.addLast(s);
            }
        }

        void queueException(Exception e) {
            queue.addLast(e);
        }

        @Override
        public NetworkInterfaceStats readStats() throws IOException {
            if (queue.isEmpty()) {
                return new NetworkInterfaceStats("wlp3s0", 0L, 0L);
            }
            Object item = queue.pollFirst();
            if (item instanceof IOException e) {
                throw e;
            } else if (item instanceof RuntimeException e) {
                throw e;
            } else if (item instanceof NetworkInterfaceStats stats) {
                return stats;
            }
            throw new IllegalStateException("Unexpected item in stub queue: " + item);
        }
    }

    // Helper time supplier for deterministic sequence of nanoTime readings
    private static class TestTimeSupplier implements LongSupplier {
        private final Deque<Long> times = new ArrayDeque<>();

        TestTimeSupplier(Long... timestamps) {
            for (Long t : timestamps) {
                times.addLast(t);
            }
        }

        @Override
        public long getAsLong() {
            return times.isEmpty() ? 0L : times.pollFirst();
        }
    }
}
