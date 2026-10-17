package com.nettrack.backend.controller;

import com.nettrack.backend.model.NetworkMonitoringState;
import com.nettrack.backend.service.NetworkMonitoringStateHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Read-only HTTP REST controller exposing the latest in-memory network monitoring state snapshot.
 */
@RestController
@RequestMapping("/api/network")
public class NetworkMonitoringController {

    private final NetworkMonitoringStateHolder stateHolder;

    public NetworkMonitoringController(NetworkMonitoringStateHolder stateHolder) {
        this.stateHolder = Objects.requireNonNull(stateHolder, "NetworkMonitoringStateHolder must not be null");
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
}
