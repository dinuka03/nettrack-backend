package com.nettrack.backend.service;

import com.nettrack.backend.model.NetworkInterfaceStats;
import com.nettrack.backend.model.NetworkRateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * On-demand stateful sampling service that pairs current network interface snapshots
 * with monotonic timestamps to calculate transfer rates.
 *
 * <p>Limitations:
 * <ul>
 *   <li>Application restart loses the in-memory baseline.</li>
 *   <li>Missed or rejected sampling intervals are not reconstructed.</li>
 *   <li>Counter decrease detection cannot identify every interface reset or recreation.</li>
 *   <li>No cumulative session, daily, or monthly totals are maintained by this service.</li>
 * </ul>
 */
@Service
public class NetworkSamplingService {

    private final ProcNetDevReaderService readerService;
    private final NetworkRateCalculatorService calculatorService;
    private final LongSupplier nanoTimeSupplier;

    private SampleState lastSample;

    private record SampleState(NetworkInterfaceStats stats, long nanoTime) {
    }

    /**
     * Production constructor autowired by Spring, using System::nanoTime for timing.
     */
    @Autowired
    public NetworkSamplingService(ProcNetDevReaderService readerService, NetworkRateCalculatorService calculatorService) {
        this(readerService, calculatorService, System::nanoTime);
    }

    /**
     * Package-private constructor for deterministic testing with custom time suppliers.
     */
    NetworkSamplingService(ProcNetDevReaderService readerService,
                           NetworkRateCalculatorService calculatorService,
                           LongSupplier nanoTimeSupplier) {
        this.readerService = Objects.requireNonNull(readerService, "ProcNetDevReaderService must not be null");
        this.calculatorService = Objects.requireNonNull(calculatorService, "NetworkRateCalculatorService must not be null");
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier, "LongSupplier nanoTimeSupplier must not be null");
    }

    /**
     * Performs an on-demand sample read and calculates rate metrics if a valid prior baseline exists.
     *
     * @return Optional containing NetworkRateResult if a valid interval elapsed, or Optional.empty() if establishing/re-establishing baseline
     * @throws IOException if reading network interface stats fails
     * @throws IllegalStateException if target interface is absent or elapsed time is non-positive
     * @throws IllegalArgumentException if interface data is malformed or invalid
     */
    public synchronized Optional<NetworkRateResult> sampleRate() throws IOException {
        NetworkInterfaceStats currentStats;
        try {
            currentStats = readerService.readStats();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            this.lastSample = null;
            throw e;
        }

        long currentNanos = nanoTimeSupplier.getAsLong();

        if (this.lastSample == null) {
            this.lastSample = new SampleState(currentStats, currentNanos);
            return Optional.empty();
        }

        long elapsedNanos = currentNanos - this.lastSample.nanoTime();
        if (elapsedNanos <= 0) {
            this.lastSample = null;
            throw new IllegalStateException("Non-positive elapsed time between samples: " + elapsedNanos + " ns");
        }

        NetworkInterfaceStats previousStats = this.lastSample.stats();
        if (currentStats.rxBytes() < previousStats.rxBytes() || currentStats.txBytes() < previousStats.txBytes()) {
            this.lastSample = new SampleState(currentStats, currentNanos);
            return Optional.empty();
        }

        NetworkRateResult result;
        try {
            result = calculatorService.calculateRate(previousStats, currentStats, Duration.ofNanos(elapsedNanos));
        } catch (IllegalArgumentException e) {
            this.lastSample = null;
            throw e;
        }

        this.lastSample = new SampleState(currentStats, currentNanos);
        return Optional.of(result);
    }
}
