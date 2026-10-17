package com.nettrack.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration enabling Spring task scheduling when periodic network monitoring is enabled.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "nettrack.monitoring.enabled", havingValue = "true", matchIfMissing = false)
public class NetworkMonitoringConfig {

    public static final String INTERVAL_PROPERTY = "nettrack.monitoring.interval-ms";
    public static final long DEFAULT_INTERVAL_MS = 1000L;

    public NetworkMonitoringConfig(Environment environment) {
        validateInterval(environment.getProperty(INTERVAL_PROPERTY));
    }

    /**
     * Validates that the configured interval is a positive integer representable as a 64-bit signed long.
     *
     * @param rawValue the raw property value
     * @return the parsed positive interval in milliseconds, or the default 1000ms if absent
     * @throws IllegalStateException if the value is blank, non-numeric, out-of-range, or non-positive
     */
    static long validateInterval(String rawValue) {
        if (rawValue == null) {
            return DEFAULT_INTERVAL_MS;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("Property '" + INTERVAL_PROPERTY
                    + "' must be a positive integer in milliseconds (representable as a 64-bit signed integer), but was blank");
        }
        long interval;
        try {
            interval = Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Property '" + INTERVAL_PROPERTY
                    + "' must be a positive integer in milliseconds (representable as a 64-bit signed integer), but was: '" + rawValue + "'");
        }
        if (interval <= 0) {
            throw new IllegalStateException("Property '" + INTERVAL_PROPERTY
                    + "' must be a positive integer in milliseconds (representable as a 64-bit signed integer), but was: " + interval);
        }
        return interval;
    }
}
