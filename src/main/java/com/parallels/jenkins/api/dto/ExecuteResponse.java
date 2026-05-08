package com.parallels.jenkins.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from {@code PUT /api/v1/machines/{id}/execute}.
 *
 * <pre>
 * {
 *   "stdout":    "Wed Apr 22 14:21:59 CEST 2026",
 *   "exit_code": 0
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecuteResponse {

    @JsonProperty("stdout")
    private String stdout;

    @JsonProperty("exit_code")
    private int exitCode;

    public ExecuteResponse() {}

    public String getStdout() { return stdout; }
    public int getExitCode() { return exitCode; }
    public void setStdout(String stdout) { this.stdout = stdout; }
    public void setExitCode(int exitCode) { this.exitCode = exitCode; }
}
