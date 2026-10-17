package com.nettrack.backend.service;

import com.nettrack.backend.model.MonitoringStatus;
import com.nettrack.backend.model.NetworkMonitoringState;
import com.nettrack.backend.model.NetworkRateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Periodically triggers network sampling and publishes the latest snapshot to NetworkMonitoringStateHolder.
 */
@Service
@ConditionalOnProperty(name = "nettrack.monitoring.enabled", havingValue = "true", matchIfMissing = false)
public class NetworkMonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(NetworkMonitoringScheduler.class);

    private final NetworkSamplingService samplingService;
    private final NetworkMonitoringStateHolder stateHolder;
    private final Clock clock;

    private boolean inErrorState = false;

    @Autowired
    public NetworkMonitoringScheduler(NetworkSamplingService samplingService, NetworkMonitoringStateHolder stateHolder) {
        this(samplingService, stateHolder, Clock.systemUTC());
    }

    NetworkMonitoringScheduler(NetworkSamplingService samplingService, NetworkMonitoringStateHolder stateHolder, Clock clock) {
        this.samplingService = Objects.requireNonNull(samplingService, "NetworkSamplingService must not be null");
        this.stateHolder = Objects.requireNonNull(stateHolder, "NetworkMonitoringStateHolder must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    /**
     * Executes a single periodic sampling attempt and publishes the resulting state snapshot.
     */
    @Scheduled(fixedDelayString = "${nettrack.monitoring.interval-ms:1000}")
    public synchronized void runPeriodicSample() {
        Optional<NetworkRateResult> sampleResult;
        boolean sampleFailed = false;

        try {
            sampleResult = samplingService.sampleRate();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            sampleFailed = true;
            sampleResult = null;
            if (!inErrorState) {
                log.warn("Network sampling failed: {}", e.getMessage());
                inErrorState = true;
            }
        }

        Instant attemptTimestamp = Instant.now(clock);

        if (sampleFailed) {
            stateHolder.updateState(new NetworkMonitoringState(MonitoringStatus.ERROR, null, attemptTimestamp));
        } else {
            if (inErrorState) {
                log.info("Network sampling recovered successfully");
                inErrorState = false;
            }

            if (sampleResult.isPresent()) {
                stateHolder.updateState(new NetworkMonitoringState(MonitoringStatus.READY, sampleResult.get(), attemptTimestamp));
            } else {
                stateHolder.updateState(new NetworkMonitoringState(MonitoringStatus.BASELINE_ONLY, null, attemptTimestamp));
            }
        }
    }
}
