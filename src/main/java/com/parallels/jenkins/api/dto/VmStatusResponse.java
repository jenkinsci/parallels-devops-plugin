package com.parallels.jenkins.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from {@code GET /api/v1/machines/{id}/status}.
 *
 * <pre>
 * {
 *   "id":            "&lt;string&gt;",
 *   "ip_configured": "&lt;string&gt;",
 *   "status":        "&lt;string&gt;"
 * }
 * </pre>
 *
 * Known {@code status} values: {@code pending}, {@code starting}, {@code running}, {@code suspended}, {@code error}.
 * The VM is SSH-ready only when {@code status} is {@code running} AND {@code ip_configured} is not {@code "-"}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VmStatusResponse {

    private String id;

    @JsonProperty("ip_configured")
    private String ipConfigured;

    private String status;

    public VmStatusResponse() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIpConfigured() {
        return ipConfigured;
    }

    public void setIpConfigured(String ipConfigured) {
        this.ipConfigured = ipConfigured;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
