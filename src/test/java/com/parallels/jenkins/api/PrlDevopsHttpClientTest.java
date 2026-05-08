package com.parallels.jenkins.api;

import com.parallels.jenkins.api.dto.CatalogManifest;
import com.parallels.jenkins.api.dto.CloneRequest;
import com.parallels.jenkins.api.dto.CloneResponse;
import com.parallels.jenkins.api.dto.CreateVmRequest;
import com.parallels.jenkins.api.dto.CreateVmResponse;
import com.parallels.jenkins.api.dto.VmStatusResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import com.parallels.jenkins.api.exception.PrlApiTimeoutException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PrlDevopsHttpClientTest {

    private MockWebServer server;
    private PrlDevopsHttpClient hostClient;
    private PrlDevopsHttpClient orchClient;

    private static final String TOKEN = "test-bearer-token";
    private static final String HOST_ID = "host-abc-123";
    private static final String VM_ID = "vm-uuid-456";

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String baseUrl = "http://" + server.getHostName() + ":" + server.getPort();

        hostClient = new PrlDevopsHttpClient.Builder()
                .baseUrl(baseUrl)
                .bearerToken(TOKEN)
                .mode(ConnectionMode.HOST)
                .build();

        orchClient = new PrlDevopsHttpClient.Builder()
                .baseUrl(baseUrl)
                .bearerToken(TOKEN)
                .mode(ConnectionMode.ORCHESTRATOR)
                .hostId(HOST_ID)
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // -------------------------------------------------------------------------
    // cloneVm — HOST mode
    // -------------------------------------------------------------------------

    @Test
    void cloneVm_hostMode_sendsCorrectRequestAndParsesResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"new-vm-789\",\"status\":\"created\",\"error\":\"\"}"));

        CloneResponse response = hostClient.cloneVm(VM_ID, new CloneRequest("my-clone", null));

        assertEquals("new-vm-789", response.getId());
        assertEquals("created", response.getStatus());

        RecordedRequest req = server.takeRequest();
        assertEquals("PUT", req.getMethod());
        assertEquals("/api/v1/machines/" + VM_ID + "/clone", req.getPath());
        assertEquals("Bearer " + TOKEN, req.getHeader("Authorization"));
        assertTrue(req.getBody().readUtf8().contains("my-clone"));
    }

    // -------------------------------------------------------------------------
    // cloneVm — ORCHESTRATOR mode
    // -------------------------------------------------------------------------

    @Test
    void cloneVm_orchestratorMode_usesOrchestratorPath() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"new-vm-789\",\"status\":\"created\",\"error\":\"\"}"));

        orchClient.cloneVm(VM_ID, new CloneRequest("orch-clone", null));

        RecordedRequest req = server.takeRequest();
        assertEquals("PUT", req.getMethod());
        assertEquals(
                "/api/v1/orchestrator/hosts/" + HOST_ID + "/machines/" + VM_ID + "/clone",
                req.getPath());
    }

    // -------------------------------------------------------------------------
    // getVmStatus
    // -------------------------------------------------------------------------

    @Test
    void getVmStatus_returnsStatusResponse() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"running\",\"ip_configured\":\"192.168.1.10\"}"));

        VmStatusResponse status = hostClient.getVmStatus(VM_ID);

        assertEquals(VM_ID, status.getId());
        assertEquals("running", status.getStatus());
        assertEquals("192.168.1.10", status.getIpConfigured());

        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/v1/machines/" + VM_ID + "/status", req.getPath());
        assertEquals("Bearer " + TOKEN, req.getHeader("Authorization"));
    }

    @Test
    void getVmStatus_orchestratorMode_usesOrchestratorPath() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"running\"}"));

        orchClient.getVmStatus(VM_ID);

        RecordedRequest req = server.takeRequest();
        assertEquals(
                "/api/v1/orchestrator/hosts/" + HOST_ID + "/machines/" + VM_ID + "/status",
                req.getPath());
    }

    // -------------------------------------------------------------------------
    // startVm
    // -------------------------------------------------------------------------

    @Test
    void startVm_hostMode_sendsCorrectGetRequest() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        assertDoesNotThrow(() -> hostClient.startVm(VM_ID));

        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/v1/machines/" + VM_ID + "/start", req.getPath());
        assertEquals("Bearer " + TOKEN, req.getHeader("Authorization"));
    }

    @Test
    void startVm_orchestratorMode_usesOrchestratorPath() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        orchClient.startVm(VM_ID);

        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals(
                "/api/v1/orchestrator/hosts/" + HOST_ID + "/machines/" + VM_ID + "/start",
                req.getPath());
    }

    // -------------------------------------------------------------------------
    // createVmFromCatalog
    // -------------------------------------------------------------------------

    @Test
    void createVmFromCatalog_sendsPostToMachinesEndpoint() throws Exception {
        String responseBody = "{\"id\":\"" + VM_ID + "\",\"name\":\"test-vm\","
                + "\"owner\":\"user\",\"current_state\":\"stopped\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        CatalogManifest manifest = new CatalogManifest(
                "EMPTY-VM", "latest", "host=user:pass@https://catalog.example.com");
        CreateVmRequest request = new CreateVmRequest("test-vm", "arm64", manifest);
        CreateVmResponse response = hostClient.createVmFromCatalog(request);

        assertEquals(VM_ID, response.getId());
        assertEquals("stopped", response.getCurrentState());

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/v1/machines", req.getPath());
        assertEquals("Bearer " + TOKEN, req.getHeader("Authorization"));
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("\"startOnCreate\":true"));
        assertTrue(body.contains("\"architecture\":\"arm64\""));
        assertTrue(body.contains("\"catalog_id\":\"EMPTY-VM\""));
    }

    @Test
    void createVmFromCatalog_usesRootPathEvenInOrchestratorMode() throws Exception {
        // catalog creation always uses /api/v1/machines, not the orchestrator path
        String responseBody = "{\"id\":\"" + VM_ID + "\",\"name\":\"test-vm\","
                + "\"owner\":\"user\",\"current_state\":\"stopped\"}";
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        CatalogManifest manifest = new CatalogManifest(
                "EMPTY-VM", "latest", "host=user:pass@https://catalog.example.com");
        CreateVmRequest request = new CreateVmRequest("test-vm", "arm64", manifest);
        orchClient.createVmFromCatalog(request);

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/api/v1/machines", req.getPath());
    }

    // -------------------------------------------------------------------------
    // deleteVm
    // -------------------------------------------------------------------------

    @Test
    void deleteVm_sends202AndNoException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202));

        assertDoesNotThrow(() -> hostClient.deleteVm(VM_ID));

        RecordedRequest req = server.takeRequest();
        assertEquals("DELETE", req.getMethod());
        assertEquals("/api/v1/machines/" + VM_ID + "?force=true", req.getPath());
        assertEquals("Bearer " + TOKEN, req.getHeader("Authorization"));
    }

    @Test
    void deleteVm_orchestratorMode_usesOrchestratorPath() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202));

        orchClient.deleteVm(VM_ID);

        RecordedRequest req = server.takeRequest();
        assertEquals("DELETE", req.getMethod());
        assertEquals(
                "/api/v1/orchestrator/hosts/" + HOST_ID + "/machines/" + VM_ID + "?force=true",
                req.getPath());
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    void nonSuccessResponse_throwsPrlApiException_with404Status() {
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":404,\"message\":\"VM not found\",\"stack\":[]}"));

        PrlApiException ex = assertThrows(PrlApiException.class, () -> hostClient.getVmStatus(VM_ID));
        assertEquals(404, ex.getHttpStatus());
        assertEquals("VM not found", ex.getMessage());
        assertNotNull(ex.getDetail());
    }

    @Test
    void serverError_throwsPrlApiException_with500Status() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":500,\"message\":\"Internal error\",\"stack\":[]}"));

        PrlApiException ex = assertThrows(PrlApiException.class, () -> hostClient.deleteVm(VM_ID));
        assertEquals(500, ex.getHttpStatus());
    }

    @Test
    void unauthorizedResponse_throwsPrlApiException_with401() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":8,\"error_description\":\"Unauthorized\",\"error_uri\":\"\"}"));

        PrlApiException ex = assertThrows(PrlApiException.class, () -> hostClient.cloneVm(VM_ID, new CloneRequest()));
        assertEquals(401, ex.getHttpStatus());
    }

    // -------------------------------------------------------------------------
    // waitForVmReady — polling
    // -------------------------------------------------------------------------

    @Test
    void waitForVmReady_returnsImmediatelyWhenAlreadyRunning() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"running\",\"ip_configured\":\"10.211.55.3\"}"));
        // execute probe
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"stdout\":\"prl-ready\",\"exit_code\":0}"));

        VmStatusResponse result = hostClient.waitForVmReady(VM_ID, "parallels", Duration.ofSeconds(5), Duration.ofMillis(50));

        assertEquals("running", result.getStatus());
        assertEquals("10.211.55.3", result.getIpConfigured());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void waitForVmReady_pollsUntilRunning() throws Exception {
        // pending → starting → running: should return on first successful execute probe
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"pending\",\"ip_configured\":\"-\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"starting\",\"ip_configured\":\"-\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"running\",\"ip_configured\":\"10.211.55.3\"}"));
        // execute probe
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"stdout\":\"prl-ready\",\"exit_code\":0}"));

        VmStatusResponse result = hostClient.waitForVmReady(VM_ID, "parallels", Duration.ofSeconds(10), Duration.ofMillis(50));

        assertEquals("running", result.getStatus());
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void waitForVmReady_executeProbe_retriesUntilSuccess() throws Exception {
        // running with valid IP but execute probe fails once before succeeding.
        // The inner loop re-fetches status after each failed attempt, so we need
        // 4 responses: status → execute(fail) → status-refetch → execute(success).
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"running\",\"ip_configured\":\"10.211.55.3\"}"));
        // first execute probe: guest agent not ready yet (500)
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
        // status re-fetch inside inner loop
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"running\",\"ip_configured\":\"10.211.55.3\"}"));
        // second execute probe: success
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"stdout\":\"prl-ready\",\"exit_code\":0}"));

        VmStatusResponse result = hostClient.waitForVmReady(VM_ID, "parallels", Duration.ofSeconds(5), Duration.ofMillis(50));

        assertEquals("running", result.getStatus());
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void waitForVmReady_runningWithValidIp_returnsAfterExecuteProbe() throws Exception {
        // running with a valid IP — should proceed to execute probe and return
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"running\",\"ip_configured\":\"10.211.55.5\"}"));
        // execute probe
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"stdout\":\"prl-ready\",\"exit_code\":0}"));

        VmStatusResponse result = hostClient.waitForVmReady(VM_ID, "parallels", Duration.ofSeconds(5), Duration.ofMillis(50));

        assertEquals("running", result.getStatus());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void waitForVmReady_suspendedState_keepsPolling() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"suspended\",\"ip_configured\":\"-\"}"));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"running\",\"ip_configured\":\"10.211.55.3\"}"));
        // execute probe
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"stdout\":\"prl-ready\",\"exit_code\":0}"));

        VmStatusResponse result = hostClient.waitForVmReady(VM_ID, "parallels", Duration.ofSeconds(5), Duration.ofMillis(50));

        assertEquals("running", result.getStatus());
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void waitForVmReady_errorState_throwsPrlApiException() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"error\"}"));

        assertThrows(PrlApiException.class,
                () -> hostClient.waitForVmReady(VM_ID, "parallels", Duration.ofSeconds(5), Duration.ofMillis(50)));
    }

    @Test
    void waitForVmReady_timeout_throwsPrlApiTimeoutException() {
        // Always return "pending" so the client never sees "running"
        for (int i = 0; i < 20; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"pending\"}"));
        }

        PrlApiTimeoutException ex = assertThrows(PrlApiTimeoutException.class,
                () -> hostClient.waitForVmReady(VM_ID, "parallels", Duration.ofMillis(200), Duration.ofMillis(50)));

        assertEquals(VM_ID, ex.getVmId());
    }

    @Test
    void waitForVmReady_statusCaseInsensitive() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"Running\",\"ip_configured\":\"10.211.55.3\"}"));
        // execute probe
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"stdout\":\"prl-ready\",\"exit_code\":0}"));

        VmStatusResponse result = hostClient.waitForVmReady(VM_ID, "parallels", Duration.ofSeconds(5), Duration.ofMillis(50));

        assertEquals("Running", result.getStatus());
    }

    // -------------------------------------------------------------------------
    // Builder validation
    // -------------------------------------------------------------------------

    @Test
    void builder_missingBaseUrl_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
                new PrlDevopsHttpClient.Builder()
                        .bearerToken(TOKEN)
                        .build());
    }

    @Test
    void builder_missingBearerToken_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
                new PrlDevopsHttpClient.Builder()
                        .baseUrl("http://localhost:8080")
                        .build());
    }

    @Test
    void builder_orchestratorModeWithoutHostId_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () ->
                new PrlDevopsHttpClient.Builder()
                        .baseUrl("http://localhost:8080")
                        .bearerToken(TOKEN)
                        .mode(ConnectionMode.ORCHESTRATOR)
                        .build());
    }
}
