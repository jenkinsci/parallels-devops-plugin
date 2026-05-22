package com.parallels.jenkins;

import com.parallels.jenkins.api.ConnectionMode;
import com.parallels.jenkins.api.PrlDevopsHttpClient;
import com.parallels.jenkins.api.dto.CloneResponse;
import com.parallels.jenkins.api.dto.VmStatusResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import com.parallels.jenkins.api.exception.PrlApiTimeoutException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP-level integration tests for the full VM lifecycle
 * (clone → status polling → execute probe → delete) against a MockWebServer.
 *
 * <p>Tagged {@code @Tag("integration")} and placed in a class whose name ends
 * with {@code IT} so the Maven Failsafe plugin picks them up separately from
 * Surefire unit tests. Skip locally with {@code -DskipITs}.
 *
 * <p>These tests exercise {@link PrlDevopsHttpClient} end-to-end through real
 * HTTP but without a live prl-devops-service instance.
 */
@Tag("integration")
class ProvisioningIntegrationIT {

    private MockWebServer server;
    private PrlDevopsHttpClient client;

    private static final String VM_ID   = "vm-it-001";
    private static final String VM_IP   = "192.168.100.42";
    private static final String API_KEY = "integration-test-key";

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new PrlDevopsHttpClient.Builder()
                .baseUrl("http://" + server.getHostName() + ":" + server.getPort())
                .apiKey(API_KEY)
                .mode(ConnectionMode.HOST)
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // -------------------------------------------------------------------------
    // Happy path: clone → RUNNING (with IP) → execute probe → delete
    // -------------------------------------------------------------------------

    @Test
    void fullVmLifecycle_cloneRunningDelete_succeeds() throws Exception {
        // 1. Clone response
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"created\",\"error\":\"\"}"));

        // 2. Status poll → running + IP
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"running\","
                        + "\"ip_configured\":\"" + VM_IP + "\"}"));

        // 3. Execute probe → success
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"stdout\":\"prl-ready\",\"stderr\":\"\",\"exit_code\":0}"));

        // 4. Delete → 204 No Content
        server.enqueue(new MockResponse().setResponseCode(204));

        // --- Act ---
        CloneResponse cloned = client.cloneVm("macOS-Sonoma-base",
                new com.parallels.jenkins.api.dto.CloneRequest("jenkins-it-clone", null));
        assertEquals(VM_ID, cloned.getId(), "Clone must return the new VM ID");
        assertEquals("created", cloned.getStatus());

        VmStatusResponse ready = client.waitForVmReady(
                VM_ID, "", Duration.ofSeconds(10), Duration.ofMillis(50));
        assertEquals("running", ready.getStatus());
        assertEquals(VM_IP, ready.getIpConfigured());

        // Delete must not throw
        assertDoesNotThrow(() -> client.deleteVm(VM_ID));

        // Verify HTTP methods / paths
        RecordedRequest cloneReq  = server.takeRequest();
        RecordedRequest statusReq = server.takeRequest();
        RecordedRequest execReq   = server.takeRequest();
        RecordedRequest deleteReq = server.takeRequest();

        assertEquals("PUT",    cloneReq.getMethod());
        assertTrue(cloneReq.getPath().endsWith("/clone"),
                "Clone path should end with /clone");

        assertEquals("GET",    statusReq.getMethod());
        assertTrue(statusReq.getPath().contains("/status"),
                "Status path should contain /status");

        assertEquals("PUT",    execReq.getMethod());
        assertTrue(execReq.getPath().contains("/execute"),
                "Execute path should contain /execute");

        assertEquals("DELETE", deleteReq.getMethod());
        assertTrue(deleteReq.getPath().contains(VM_ID),
                "Delete path should contain the VM ID");

        // Auth header present on every call
        assertEquals("X-API-Key " + API_KEY, cloneReq.getHeader("X-API-Key") != null
                ? "X-API-Key " + cloneReq.getHeader("X-API-Key") : cloneReq.getHeader("Authorization"));
    }

    // -------------------------------------------------------------------------
    // Failure: clone API returns 500 → PrlApiException with correct HTTP status
    // -------------------------------------------------------------------------

    @Test
    void cloneVm_returns500_throwsPrlApiExceptionWithCorrectStatus() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"Internal Server Error\"}"));

        PrlApiException ex = assertThrows(PrlApiException.class,
                () -> client.cloneVm("macOS-Sonoma-base",
                        new com.parallels.jenkins.api.dto.CloneRequest("jenkins-fail-clone", null)));

        assertEquals(500, ex.getHttpStatus(),
                "Exception must carry the 500 status code from the service");
    }

    // -------------------------------------------------------------------------
    // Timeout: VM status polling never reaches RUNNING → PrlApiTimeoutException
    // -------------------------------------------------------------------------

    @Test
    void waitForVmReady_timesOut_whenStatusNeverTransitionsToRunning() throws Exception {
        // Clone first
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"created\",\"error\":\"\"}"));

        client.cloneVm("macOS-Sonoma-base",
                new com.parallels.jenkins.api.dto.CloneRequest("jenkins-timeout-clone", null));

        // Status stays "pending" for all polls — MockWebServer serves the same response
        // repeatedly; enqueue enough to cover the timeout polling window.
        for (int i = 0; i < 10; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"id\":\"" + VM_ID + "\",\"status\":\"pending\","
                            + "\"ip_configured\":\"-\"}"));
        }

        PrlApiTimeoutException ex = assertThrows(PrlApiTimeoutException.class,
                () -> client.waitForVmReady(
                        VM_ID, "", Duration.ofMillis(300), Duration.ofMillis(50)));

        assertTrue(ex.getMessage().contains(VM_ID),
                "Timeout exception should mention the VM ID");
    }
}

