package com.nettrack.backend.controller;

import com.nettrack.backend.model.NetworkIntervalUsage;
import com.nettrack.backend.model.NetworkMonitoringState;
import com.nettrack.backend.repository.NetworkIntervalUsageRepository;
import com.nettrack.backend.service.NetworkMonitoringStateHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Read-only HTTP REST controller exposing the latest in-memory network monitoring state snapshot
 * and persisted usage history.
 */
@RestController
@RequestMapping("/api/network")
public class NetworkMonitoringController {

    public static final int DEFAULT_HISTORY_LIMIT = 100;
    public static final int MIN_HISTORY_LIMIT = 1;
    public static final int MAX_HISTORY_LIMIT = 1000;

    private final NetworkMonitoringStateHolder stateHolder;
    private final NetworkIntervalUsageRepository repository;

    public NetworkMonitoringController(NetworkMonitoringStateHolder stateHolder,
                                       NetworkIntervalUsageRepository repository) {
        this.stateHolder = Objects.requireNonNull(stateHolder, "NetworkMonitoringStateHolder must not be null");
        this.repository = Objects.requireNonNull(repository, "NetworkIntervalUsageRepository must not be null");
    }

    /**
     * Returns the latest stored network monitoring state snapshot.
     *
     * @return HTTP 200 with Cache-Control: no-store and the immutable NetworkMonitoringState
     */
    @GetMapping(path = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NetworkMonitoringState> getCurrentState() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(stateHolder.getCurrentState());
    }

    /**
     * Returns recent network interval usage records ordered newest first.
     *
     * @param limit maximum number of records to return (1 to 1000, default 100)
     * @return HTTP 200 with Cache-Control: no-store and list of records, or HTTP 400 if limit is out of bounds
     */
    @GetMapping(path = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<NetworkIntervalUsage>> getHistory(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (limit < MIN_HISTORY_LIMIT || limit > MAX_HISTORY_LIMIT) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(repository.findRecent(limit));
    }
}
