package com.parallels.jenkins.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parallels.jenkins.api.dto.CloneRequest;
import com.parallels.jenkins.api.dto.CloneResponse;
import com.parallels.jenkins.api.dto.CreateVmRequest;
import com.parallels.jenkins.api.dto.CreateVmResponse;
import com.parallels.jenkins.api.dto.ExecuteRequest;
import com.parallels.jenkins.api.dto.ExecuteResponse;
import com.parallels.jenkins.api.dto.VmStatusResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import com.parallels.jenkins.api.exception.PrlApiTimeoutException;
import hudson.util.Secret;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * JDK {@link HttpClient}-based implementation of {@link PrlDevopsApiClient}.
 *
 * <p>Construct via {@link Builder}:
 * <pre>
 *   PrlDevopsApiClient client = new PrlDevopsHttpClient.Builder()
 *       .baseUrl("https://my-host:8080")
 *       .bearerToken("secret-token")
 *       .mode(ConnectionMode.HOST)
 *       .build();
 * </pre>
 *
 * For orchestrator mode, no {@code hostId} is needed — the orchestrator
 * routes requests to the appropriate host internally:
 * <pre>
 *   PrlDevopsApiClient client = new PrlDevopsHttpClient.Builder()
 *       .baseUrl("https://orchestrator:8080")
 *       .bearerToken("secret-token")
 *       .mode(ConnectionMode.ORCHESTRATOR)
 *       .build();
 * </pre>
 *
 * <p>Thread-safe: a single instance may be shared across threads.
 */
public class PrlDevopsHttpClient implements PrlDevopsApiClient {

    private static final String CONTENT_TYPE_JSON = "application/json";

    private final String baseUrl;
    private final String authHeaderName;
    private final Secret authHeaderValue;
    private final ConnectionMode mode;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    private PrlDevopsHttpClient(Builder builder) {
        this.baseUrl = stripTrailingSlash(builder.baseUrl);
        this.authHeaderName = builder.authHeaderName;
        this.authHeaderValue = Secret.fromString(Secret.toString(builder.authHeaderValue));
        this.mode = builder.mode;
        this.httpClient = builder.httpClient != null
                ? builder.httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // -------------------------------------------------------------------------
    // Interface implementation
    // -------------------------------------------------------------------------

    @Override
    public CloneResponse cloneVm(String sourceVmId, CloneRequest request) throws PrlApiException {
        String path = machinePath(sourceVmId) + "/clone";
        String body = serialize(request);
        HttpResponse<String> response = send(
                HttpRequest.newBuilder()
                        .uri(toUri(path))
                        .header("Content-Type", CONTENT_TYPE_JSON)
                        .header(authHeaderName, authHeaderValue.getPlainText())
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build());
        requireSuccessful(response);
        return deserialize(response.body(), CloneResponse.class);
    }

    @Override
    public CreateVmResponse createVmFromCatalog(CreateVmRequest request) throws PrlApiException {
        String path = mode == ConnectionMode.ORCHESTRATOR
                ? "/api/v1/orchestrator/machines"
                : "/api/v1/machines";
        String body = serialize(request);
        HttpResponse<String> response = send(
                HttpRequest.newBuilder()
                        .uri(toUri(path))
                        .header("Content-Type", CONTENT_TYPE_JSON)
                        .header(authHeaderName, authHeaderValue.getPlainText())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build());
        requireSuccessful(response);
        return deserialize(response.body(), CreateVmResponse.class);
    }

    @Override
    public VmStatusResponse getVmStatus(String vmId) throws PrlApiException {
        String path = machinePath(vmId) + "/status";
        HttpResponse<String> response = send(
                HttpRequest.newBuilder()
                        .uri(toUri(path))
                        .header(authHeaderName, authHeaderValue.getPlainText())
                        .GET()
                        .build());
        requireSuccessful(response);
        return deserialize(response.body(), VmStatusResponse.class);
    }

    @Override
    public void startVm(String vmId) throws PrlApiException {
        String path = machinePath(vmId) + "/start";
        HttpResponse<String> response = send(
                HttpRequest.newBuilder()
                        .uri(toUri(path))
                        .header(authHeaderName, authHeaderValue.getPlainText())
                        .GET()
                        .build());
        requireSuccessful(response);
    }

    @Override
    public void deleteVm(String vmId) throws PrlApiException {
        String path = machinePath(vmId) + "?force=true";
        HttpResponse<String> response = send(
                HttpRequest.newBuilder()
                        .uri(toUri(path))
                        .header(authHeaderName, authHeaderValue.getPlainText())
                        .DELETE()
                        .build());
        requireSuccessful(response);
    }

    @Override
    public ExecuteResponse executeCommand(String vmId, ExecuteRequest request) throws PrlApiException {
        String path = machinePath(vmId) + "/execute";
        String body = serialize(request);
        HttpResponse<String> response = send(
                HttpRequest.newBuilder()
                        .uri(toUri(path))
                        .header("Content-Type", CONTENT_TYPE_JSON)
                        .header(authHeaderName, authHeaderValue.getPlainText())
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build());
        requireSuccessful(response);
        return deserialize(response.body(), ExecuteResponse.class);
    }

    @Override
    public VmStatusResponse waitForVmReady(String vmId, String vmUser, Duration timeout, Duration interval)
            throws PrlApiException, PrlApiTimeoutException {

        Instant deadline = Instant.now().plus(timeout);

        while (true) {
            VmStatusResponse status = getVmStatus(vmId);
            String state = status.getStatus() != null ? status.getStatus().toLowerCase(Locale.ROOT) : "";

            switch (state) {
                case "running":
                    // VM OS is up — probe the execute API in a loop until it accepts commands
                    // AND ip_configured is a real IP address (not "-" or blank).
                    // The Parallels guest agent and IP assignment may still be initialising
                    // even after the VM reports "running".
                    while (true) {
                        boolean ipReady = isValidIp(status.getIpConfigured());
                        boolean execReady = false;
                        if (ipReady) {
                            try {
                                ExecuteResponse probe = executeCommand(
                                        vmId,
                                        new ExecuteRequest("echo prl-ready", vmUser,
                                                Collections.emptyMap()));
                                execReady = probe.getExitCode() == 0;
                            } catch (PrlApiException ignored) {
                                // Execute API not yet ready — keep retrying
                            }
                        }
                        if (ipReady && execReady) {
                            return status; // IP assigned + execute API confirmed working
                        }
                        if (Instant.now().isAfter(deadline)) {
                            throw new PrlApiTimeoutException(vmId, timeout);
                        }
                        sleep(interval);
                        // Re-fetch status so ip_configured is updated each iteration.
                        status = getVmStatus(vmId);
                        if (Instant.now().isAfter(deadline)) {
                            throw new PrlApiTimeoutException(vmId, timeout);
                        }
                    }
                case "error":
                    throw new PrlApiException("VM '" + vmId + "' entered error state");
                case "stopped":
                case "pending":
                case "starting":
                case "suspended":
                    // keep polling — transient states on the way to running
                    break;
                default:
                    throw new PrlApiException(
                            "VM '" + vmId + "' reported unexpected state: '" + status.getStatus() + "'");
            }

            if (Instant.now().isAfter(deadline)) {
                throw new PrlApiTimeoutException(vmId, timeout);
            }

            sleep(interval);

            // Re-check deadline after sleep to avoid one extra poll after expiry
            if (Instant.now().isAfter(deadline)) {
                throw new PrlApiTimeoutException(vmId, timeout);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Path building
    // -------------------------------------------------------------------------

    /**
     * Returns the path to {@code /machines/{vmId}}, adjusted for the current
     * {@link ConnectionMode}.
     */
    private String machinePath(String vmId) {
        if (mode == ConnectionMode.ORCHESTRATOR) {
            return "/api/v1/orchestrator/machines/" + vmId;
        }
        return "/api/v1/machines/" + vmId;
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private HttpResponse<String> send(HttpRequest request) throws PrlApiException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PrlApiException("Network error communicating with prl-devops-service: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrlApiException("Request interrupted: " + e.getMessage(), e);
        }
    }

    private void requireSuccessful(HttpResponse<String> response) throws PrlApiException {
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            throw PrlApiException.fromResponse(status, response.body(), mapper);
        }
    }

    private URI toUri(String path) {
        return URI.create(baseUrl + path);
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    private String serialize(Object obj) throws PrlApiException {
        try {
            return mapper.writeValueAsString(obj);
        } catch (IOException e) {
            throw new PrlApiException("Failed to serialise request body: " + e.getMessage(), e);
        }
    }

    private <T> T deserialize(String body, Class<T> type) throws PrlApiException {
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new PrlApiException("Failed to parse response body as " + type.getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    private static String stripTrailingSlash(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }

    private static void sleep(Duration duration) throws PrlApiException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrlApiException("Polling interrupted", e);
        }
    }

    /** Returns true if {@code ip} is a non-blank, non-placeholder IP address. */
    private static boolean isValidIp(String ip) {
        return ip != null && !ip.isBlank() && !ip.equals("-");
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {

        private String baseUrl;
        private String authHeaderName;
        private Secret authHeaderValue;
        private ConnectionMode mode = ConnectionMode.HOST;
        /** Allows injection of a custom HttpClient (primarily for testing). */
        HttpClient httpClient;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Use for username+password auth: sends {@code Authorization: Bearer <token>}. */
        public Builder bearerToken(String token) {
            this.authHeaderName = "Authorization";
            this.authHeaderValue = Secret.fromString("Bearer " + token);
            return this;
        }

        /** Use for API key auth: sends {@code X-API-Key: <encoded>}. */
        public Builder apiKey(String encoded) {
            this.authHeaderName = "X-API-Key";
            this.authHeaderValue = Secret.fromString(encoded);
            return this;
        }

        public Builder mode(ConnectionMode mode) {
            this.mode = mode;
            return this;
        }

        /** Package-private — intended for test use only. */
        Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public PrlDevopsHttpClient build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("baseUrl must be set");
            }
            if (authHeaderName == null || authHeaderValue == null
                    || authHeaderValue.getPlainText().isBlank()) {
                throw new IllegalStateException(
                        "Either bearerToken() or apiKey() must be called before build()");
            }
            return new PrlDevopsHttpClient(this);
        }
    }
}
