package com.nettrack.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nettrack.backend.model.MonitoringStatus;
import com.nettrack.backend.model.NetworkMonitoringState;
import com.nettrack.backend.model.NetworkRateResult;
import com.nettrack.backend.model.NetworkIntervalUsage;
import com.nettrack.backend.repository.NetworkIntervalUsageRepository;
import com.nettrack.backend.service.NetworkMonitoringStateHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @MockitoBean
    private NetworkIntervalUsageRepository repository;

    private static final Instant T1 = Instant.parse("2026-10-17T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-17T12:00:01Z");
    private static final Instant T3 = Instant.parse("2026-10-17T12:00:02Z");
    private static final Instant T4 = Instant.parse("2026-10-17T12:00:03Z");
    private static final Instant T_FRACTIONAL = Instant.parse("2026-10-17T12:00:00.123456789Z");

    @Test
    @DisplayName("1. Constructor rejects null dependencies")
    void constructorRejectsNullDependencies() {
        assertThrows(NullPointerException.class, () -> new NetworkMonitoringController(null, repository));
        assertThrows(NullPointerException.class, () -> new NetworkMonitoringController(stateHolder, null));
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

    @Test
    @DisplayName("12. Omitting limit calls repository with default 100 and returns HTTP 200 with no-store")
    void historyEndpointDefaultsLimitTo100() throws Exception {
        when(repository.findRecent(100)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/network/history"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("[]"));

        verify(repository, times(1)).findRecent(100);
    }

    @Test
    @DisplayName("13. Custom limit is forwarded to repository")
    void historyEndpointForwardsCustomLimit() throws Exception {
        when(repository.findRecent(25)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/network/history").param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("[]"));

        verify(repository, times(1)).findRecent(25);
    }

    @Test
    @DisplayName("14. Empty repository result returns HTTP 200 and empty JSON array")
    void historyEndpointReturnsEmptyArrayWhenNoRecords() throws Exception {
        when(repository.findRecent(100)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/network/history"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("15. Returned JSON contains expected fields and ISO-8601 UTC timestamp")
    void historyEndpointReturnsExpectedFieldsAndTimestamp() throws Exception {
        NetworkIntervalUsage record = new NetworkIntervalUsage(
                42L, "wlp3s0", 12345L, 6789L, 1.998, Instant.parse("2026-10-22T08:30:00Z")
        );
        when(repository.findRecent(100)).thenReturn(List.of(record));

        mockMvc.perform(get("/api/network/history"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].interfaceName").value("wlp3s0"))
                .andExpect(jsonPath("$[0].rxBytesDelta").value(12345))
                .andExpect(jsonPath("$[0].txBytesDelta").value(6789))
                .andExpect(jsonPath("$[0].durationSeconds").value(1.998))
                .andExpect(jsonPath("$[0].recordedAt").value("2026-10-22T08:30:00Z"));

        verify(repository, times(1)).findRecent(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "1001"})
    @DisplayName("16. Limits outside 1..1000 produce HTTP 400 and do not query repository")
    void historyEndpointRejectsInvalidLimits(String invalidLimit) throws Exception {
        mockMvc.perform(get("/api/network/history").param("limit", invalidLimit))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("17. Non-numeric limit produces HTTP 400 and does not query repository")
    void historyEndpointRejectsNonNumericLimit() throws Exception {
        mockMvc.perform(get("/api/network/history").param("limit", "invalid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(repository);
    }
}
