package com.nettrack.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nettrack.backend.model.MonitoringStatus;
import com.nettrack.backend.model.NetworkMonitoringState;
import com.nettrack.backend.model.NetworkRateResult;
import com.nettrack.backend.service.NetworkMonitoringStateHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NetworkMonitoringController.class)
@TestPropertySource(properties = "nettrack.monitoring.enabled=false")
class NetworkMonitoringControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NetworkMonitoringStateHolder stateHolder;

    private static final Instant T1 = Instant.parse("2026-10-17T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-17T12:00:01Z");
    private static final Instant T3 = Instant.parse("2026-10-17T12:00:02Z");
    private static final Instant T4 = Instant.parse("2026-10-17T12:00:03Z");
    private static final Instant T_FRACTIONAL = Instant.parse("2026-10-17T12:00:00.123456789Z");

    @Test
    @DisplayName("1. Constructor rejects null NetworkMonitoringStateHolder")
    void constructorRejectsNullStateHolder() {
        assertThrows(NullPointerException.class, () -> new NetworkMonitoringController(null));
    }

    @Test
    @DisplayName("2. INITIAL returns HTTP 200 with status and both nullable fields explicitly present as null")
    void initialReturnsOkWithExplicitNulls() throws Exception {
        NetworkMonitoringState initialState = new NetworkMonitoringState(MonitoringStatus.INITIAL, null, null);
        when(stateHolder.getCurrentState()).thenReturn(initialState);

        MvcResult result = mockMvc.perform(get("/api/network/current"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("INITIAL", root.path("status").asText());

        // Explicit null check: proved that keys exist AND their values are null
        assertTrue(root.has("rateResult"), "Key 'rateResult' must exist in JSON");
        assertTrue(root.get("rateResult").isNull(), "Key 'rateResult' must be explicitly null");

        assertTrue(root.has("lastAttemptTimestamp"), "Key 'lastAttemptTimestamp' must exist in JSON");
        assertTrue(root.get("lastAttemptTimestamp").isNull(), "Key 'lastAttemptTimestamp' must be explicitly null");

        verify(stateHolder, times(1)).getCurrentState();
    }

    @Test
    @DisplayName("3. BASELINE_ONLY returns HTTP 200, explicit null rateResult, and formatted timestamp")
    void baselineOnlyReturnsOkWithTimestamp() throws Exception {
        NetworkMonitoringState baselineState = new NetworkMonitoringState(MonitoringStatus.BASELINE_ONLY, null, T1);
        when(stateHolder.getCurrentState()).thenReturn(baselineState);

        MvcResult result = mockMvc.perform(get("/api/network/current"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("BASELINE_ONLY", root.path("status").asText());

        assertTrue(root.has("rateResult"), "Key 'rateResult' must exist in JSON");
        assertTrue(root.get("rateResult").isNull(), "Key 'rateResult' must be explicitly null");

        assertTrue(root.has("lastAttemptTimestamp"), "Key 'lastAttemptTimestamp' must exist in JSON");
        assertEquals("2026-10-17T12:00:00Z", root.path("lastAttemptTimestamp").asText());

        verify(stateHolder, times(1)).getCurrentState();
    }

    @Test
    @DisplayName("4. READY returns all nested fields with correct numeric values and ISO timestamp")
    void readyReturnsAllNestedRateFields() throws Exception {
        NetworkRateResult rateResult = new NetworkRateResult("wlp3s0", 4000L, 1000L, 2000.0, 500.0, 2.0);
        NetworkMonitoringState readyState = new NetworkMonitoringState(MonitoringStatus.READY, rateResult, T2);
        when(stateHolder.getCurrentState()).thenReturn(readyState);

        MvcResult result = mockMvc.perform(get("/api/network/current"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("READY", root.path("status").asText());

        JsonNode rateNode = root.path("rateResult");
        assertNotNull(rateNode);
        assertFalse(rateNode.isNull());
        assertEquals("wlp3s0", rateNode.path("interfaceName").asText());
        assertEquals(4000L, rateNode.path("rxBytesDelta").asLong());
        assertEquals(1000L, rateNode.path("txBytesDelta").asLong());
        assertEquals(2000.0, rateNode.path("rxBytesPerSecond").asDouble(), 1e-6);
        assertEquals(500.0, rateNode.path("txBytesPerSecond").asDouble(), 1e-6);
        assertEquals(2.0, rateNode.path("durationSeconds").asDouble(), 1e-6);

        assertEquals("2026-10-17T12:00:01Z", root.path("lastAttemptTimestamp").asText());
        verify(stateHolder, times(1)).getCurrentState();
    }

    @Test
    @DisplayName("5. READY with zero rates remains a valid present result")
    void readyWithZeroRatesReturnsCorrectly() throws Exception {
        NetworkRateResult zeroRate = new NetworkRateResult("wlp3s0", 0L, 0L, 0.0, 0.0, 1.0);
        NetworkMonitoringState zeroState = new NetworkMonitoringState(MonitoringStatus.READY, zeroRate, T3);
        when(stateHolder.getCurrentState()).thenReturn(zeroState);

        MvcResult result = mockMvc.perform(get("/api/network/current"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("READY", root.path("status").asText());

        JsonNode rateNode = root.path("rateResult");
        assertEquals(0L, rateNode.path("rxBytesDelta").asLong());
        assertEquals(0L, rateNode.path("txBytesDelta").asLong());
        assertEquals(0.0, rateNode.path("rxBytesPerSecond").asDouble(), 1e-6);
        assertEquals(0.0, rateNode.path("txBytesPerSecond").asDouble(), 1e-6);
        assertEquals(1.0, rateNode.path("durationSeconds").asDouble(), 1e-6);

        assertEquals("2026-10-17T12:00:02Z", root.path("lastAttemptTimestamp").asText());
    }

    @Test
    @DisplayName("6. ERROR returns HTTP 200, explicit null rateResult, and lastAttemptTimestamp")
    void errorReturnsOkWithNullRateResult() throws Exception {
        NetworkMonitoringState errorState = new NetworkMonitoringState(MonitoringStatus.ERROR, null, T4);
        when(stateHolder.getCurrentState()).thenReturn(errorState);

        MvcResult result = mockMvc.perform(get("/api/network/current"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("ERROR", root.path("status").asText());

        assertTrue(root.has("rateResult"), "Key 'rateResult' must exist in JSON");
        assertTrue(root.get("rateResult").isNull(), "Key 'rateResult' must be explicitly null");

        assertTrue(root.has("lastAttemptTimestamp"), "Key 'lastAttemptTimestamp' must exist in JSON");
        assertEquals("2026-10-17T12:00:03Z", root.path("lastAttemptTimestamp").asText());

        verify(stateHolder, times(1)).getCurrentState();
    }

    @Test
    @DisplayName("7. ISO-8601 UTC serialization correctly preserves fractional seconds")
    void iso8601SerializationPreservesFractionalSeconds() throws Exception {
        NetworkMonitoringState fractionalState = new NetworkMonitoringState(MonitoringStatus.BASELINE_ONLY, null, T_FRACTIONAL);
        when(stateHolder.getCurrentState()).thenReturn(fractionalState);

        MvcResult result = mockMvc.perform(get("/api/network/current"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("2026-10-17T12:00:00.123456789Z", root.path("lastAttemptTimestamp").asText());
    }

    @Test
    @DisplayName("8. Exactly one state-holder read per request")
    void exactlyOneReadPerRequest() throws Exception {
        when(stateHolder.getCurrentState()).thenReturn(new NetworkMonitoringState(MonitoringStatus.INITIAL, null, null));

        mockMvc.perform(get("/api/network/current"))
                .andExpect(status().isOk());

        verify(stateHolder, times(1)).getCurrentState();
    }

    @Test
    @DisplayName("9. Repeated requests return same snapshot without modifying it")
    void repeatedRequestsReturnSameSnapshot() throws Exception {
        NetworkMonitoringState state = new NetworkMonitoringState(MonitoringStatus.BASELINE_ONLY, null, T1);
        when(stateHolder.getCurrentState()).thenReturn(state);

        MvcResult r1 = mockMvc.perform(get("/api/network/current")).andExpect(status().isOk()).andReturn();
        MvcResult r2 = mockMvc.perform(get("/api/network/current")).andExpect(status().isOk()).andReturn();

        assertEquals(r1.getResponse().getContentAsString(), r2.getResponse().getContentAsString());
        verify(stateHolder, times(2)).getCurrentState();
    }

    @Test
    @DisplayName("10. A later request returns newly supplied snapshot rather than controller-cached copy")
    void laterRequestReturnsFreshSnapshot() throws Exception {
        NetworkMonitoringState stateA = new NetworkMonitoringState(MonitoringStatus.INITIAL, null, null);
        NetworkMonitoringState stateB = new NetworkMonitoringState(MonitoringStatus.BASELINE_ONLY, null, T1);

        when(stateHolder.getCurrentState()).thenReturn(stateA);
        MvcResult r1 = mockMvc.perform(get("/api/network/current")).andExpect(status().isOk()).andReturn();
        JsonNode node1 = objectMapper.readTree(r1.getResponse().getContentAsString());
        assertEquals("INITIAL", node1.path("status").asText());

        // Update state in holder mock
        reset(stateHolder);
        when(stateHolder.getCurrentState()).thenReturn(stateB);

        MvcResult r2 = mockMvc.perform(get("/api/network/current")).andExpect(status().isOk()).andReturn();
        JsonNode node2 = objectMapper.readTree(r2.getResponse().getContentAsString());
        assertEquals("BASELINE_ONLY", node2.path("status").asText());
        assertEquals("2026-10-17T12:00:00Z", node2.path("lastAttemptTimestamp").asText());
    }

    @Test
    @DisplayName("11. MVC test slice context does not contain reader, sampler, calculator, or scheduler beans")
    void mvcContextDoesNotContainServiceOrSchedulerBeans() {
        assertFalse(applicationContext.containsBean("networkMonitoringScheduler"),
                "networkMonitoringScheduler must not be loaded in MVC slice");
        assertFalse(applicationContext.containsBean("networkSamplingService"),
                "networkSamplingService must not be loaded in MVC slice");
        assertFalse(applicationContext.containsBean("networkRateCalculatorService"),
                "networkRateCalculatorService must not be loaded in MVC slice");
        assertFalse(applicationContext.containsBean("procNetDevReaderService"),
                "procNetDevReaderService must not be loaded in MVC slice");
    }
}
