package com.parallels.jenkins.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Request body for {@code PUT /api/v1/machines/{id}/execute}.
 *
 * <pre>
 * {
 *   "command": "echo hello",
 *   "user": "parallels",
 *   "environment_variables": { "KEY": "value" }
 * }
 * </pre>
 */
public class ExecuteRequest {

    @JsonProperty("command")
    private final String command;

    @JsonProperty("user")
    private final String user;

    @JsonProperty("environment_variables")
    private final Map<String, String> environmentVariables;

    public ExecuteRequest(String command, String user, Map<String, String> environmentVariables) {
        this.command = command;
        this.user = user;
        this.environmentVariables = environmentVariables;
    }

    public String getCommand() { return command; }
    public String getUser() { return user; }
    public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
}
