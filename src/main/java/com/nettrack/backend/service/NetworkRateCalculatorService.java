package com.nettrack.backend.service;

import com.nettrack.backend.model.NetworkInterfaceStats;
import com.nettrack.backend.model.NetworkRateResult;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Stateless service for calculating network byte deltas and transfer rates
 * over a specified time duration between two snapshots.
 */
@Service
public class NetworkRateCalculatorService {

    private static final String TARGET_INTERFACE = "wlp3s0";
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /**
     * Calculates transfer rates and byte deltas between start and end snapshots over duration.
     *
     * @param start    initial interface snapshot
     * @param end      subsequent interface snapshot
     * @param duration time elapsed between start and end snapshots
     * @return NetworkRateResult metrics
     * @throws IllegalArgumentException if any argument is null, duration is non-positive,
     *                                  interfaces do not match wlp3s0, counters are negative,
     *                                  or counters decrease
     */
    public NetworkRateResult calculateRate(NetworkInterfaceStats start, NetworkInterfaceStats end, Duration duration) {
        if (start == null || end == null || duration == null) {
            throw new IllegalArgumentException("Start snapshot, end snapshot, and duration must not be null");
        }

        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Duration must be strictly positive: " + duration);
        }

        if (start.interfaceName() == null || end.interfaceName() == null) {
            throw new IllegalArgumentException("Interface name must not be null");
        }

        if (!start.interfaceName().equals(end.interfaceName())) {
            throw new IllegalArgumentException("Interface names must match: start="
                    + start.interfaceName() + ", end=" + end.interfaceName());
        }

        if (!TARGET_INTERFACE.equals(start.interfaceName())) {
            throw new IllegalArgumentException("Only interface " + TARGET_INTERFACE + " is supported; found: "
                    + start.interfaceName());
        }

        if (start.rxBytes() < 0 || start.txBytes() < 0 || end.rxBytes() < 0 || end.txBytes() < 0) {
            throw new IllegalArgumentException("Input counters must be non-negative");
        }

        if (end.rxBytes() < start.rxBytes() || end.txBytes() < start.txBytes()) {
            throw new IllegalArgumentException("End counters cannot be less than start counters (counter decrease detected)");
        }

        long rxBytesDelta = end.rxBytes() - start.rxBytes();
        long txBytesDelta = end.txBytes() - start.txBytes();

        double durationSeconds = duration.getSeconds() + (duration.getNano() / NANOS_PER_SECOND);

        double rxBytesPerSecond = rxBytesDelta / durationSeconds;
        double txBytesPerSecond = txBytesDelta / durationSeconds;

        return new NetworkRateResult(
                TARGET_INTERFACE,
                rxBytesDelta,
                txBytesDelta,
                rxBytesPerSecond,
                txBytesPerSecond,
                durationSeconds
        );
    }
}
