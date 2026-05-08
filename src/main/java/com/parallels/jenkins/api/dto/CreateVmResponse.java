package com.parallels.jenkins.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from {@code POST /api/v1/machines} (catalog provisioning).
 *
 * <pre>
 * {
 *   "id":            "30924867-1dc9-4cb4-8eb5-e1262c1ab863",
 *   "name":          "test",
 *   "owner":         "saikumar.peddireddy",
 *   "current_state": "stopped"
 * }
 * </pre>
 *
 * Note: the field is {@code current_state} (not {@code status} as in {@link VmStatusResponse}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateVmResponse {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("owner")
    private String owner;

    @JsonProperty("current_state")
    private String currentState;

    public CreateVmResponse() {}

    public String getId() { return id; }
    public String getName() { return name; }
    public String getOwner() { return owner; }
    public String getCurrentState() { return currentState; }

    public void setId(String id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setOwner(String owner) { this.owner = owner; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }
}
