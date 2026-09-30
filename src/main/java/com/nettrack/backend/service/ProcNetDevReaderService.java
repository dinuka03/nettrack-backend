package com.nettrack.backend.service;

import com.nettrack.backend.model.NetworkInterfaceStats;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Service for reading raw network interface byte counters from /proc/net/dev.
 * Specifically monitors the physical Wi-Fi interface wlp3s0.
 */
@Service
public class ProcNetDevReaderService {

    private static final String DEFAULT_FILE_PATH = "/proc/net/dev";
    private static final String TARGET_INTERFACE = "wlp3s0";
    private static final int EXPECTED_FIELD_COUNT = 16;
    private static final int RX_BYTES_INDEX = 0;
    private static final int TX_BYTES_INDEX = 8;

    private final Path procNetDevPath;

    public ProcNetDevReaderService() {
        this.procNetDevPath = Path.of(DEFAULT_FILE_PATH);
    }

    /**
     * Reads current statistics for wlp3s0 from /proc/net/dev.
     *
     * @return NetworkInterfaceStats snapshot
     * @throws IOException           if reading /proc/net/dev fails
     * @throws IllegalStateException if wlp3s0 interface is absent
     * @throws IllegalArgumentException if wlp3s0 row is malformed or has invalid counters
     */
    public NetworkInterfaceStats readStats() throws IOException {
        List<String> lines = Files.readAllLines(procNetDevPath);
        return parseLines(lines);
    }

    /**
     * Parses lines from /proc/net/dev format to extract wlp3s0 statistics.
     * Package-private to allow unit testing without reading host filesystem.
     *
     * @param lines list of lines from /proc/net/dev
     * @return NetworkInterfaceStats snapshot for wlp3s0
     * @throws IllegalStateException if wlp3s0 interface is absent
     * @throws IllegalArgumentException if wlp3s0 row is malformed or has invalid counters
     */
    NetworkInterfaceStats parseLines(List<String> lines) {
        if (lines != null) {
            for (String line : lines) {
                int colonIndex = line.indexOf(':');
                if (colonIndex == -1) {
                    continue;
                }

                String interfaceName = line.substring(0, colonIndex).trim();
                if (!TARGET_INTERFACE.equals(interfaceName)) {
                    continue;
                }

                String counterSection = line.substring(colonIndex + 1).trim();
                if (counterSection.isEmpty()) {
                    throw new IllegalArgumentException("Malformed row for interface " + TARGET_INTERFACE + ": empty counter section");
                }

                String[] fields = counterSection.split("\\s+");
                if (fields.length != EXPECTED_FIELD_COUNT) {
                    throw new IllegalArgumentException("Malformed row for interface " + TARGET_INTERFACE
                            + ": expected " + EXPECTED_FIELD_COUNT + " fields, but found " + fields.length);
                }

                long rxBytes;
                long txBytes;
                try {
                    rxBytes = Long.parseLong(fields[RX_BYTES_INDEX]);
                    txBytes = Long.parseLong(fields[TX_BYTES_INDEX]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid nonnumeric or out-of-range counter value for "
                            + TARGET_INTERFACE, e);
                }

                if (rxBytes < 0 || txBytes < 0) {
                    throw new IllegalArgumentException("Counter values for " + TARGET_INTERFACE + " must be non-negative");
                }

                return new NetworkInterfaceStats(TARGET_INTERFACE, rxBytes, txBytes);
            }
        }

        throw new IllegalStateException("Target interface " + TARGET_INTERFACE + " not found in " + DEFAULT_FILE_PATH);
    }
}
