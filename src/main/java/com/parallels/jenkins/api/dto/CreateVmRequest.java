package com.parallels.jenkins.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code POST /api/v1/machines} (catalog provisioning).
 *
 * <pre>
 * {
 *   "name":            "jenkins-clone-1234",
 *   "start_on_create": true,
 *   "architecture":    "arm64",
 *   "catalog_manifest": { ... }
 * }
 * </pre>
 */
public class CreateVmRequest {

    @JsonProperty("name")
    private final String name;

    @JsonProperty("start_on_create")
    private final boolean startOnCreate;

    @JsonProperty("architecture")
    private final String architecture;

    @JsonProperty("catalog_manifest")
    private final CatalogManifest catalogManifest;

    public CreateVmRequest(String name, String architecture, CatalogManifest catalogManifest) {
        this.name = name;
        this.startOnCreate = true; // always true — Jenkins needs the VM running
        this.architecture = architecture;
        this.catalogManifest = catalogManifest;
    }

    public String getName() { return name; }
    public boolean isStartOnCreate() { return startOnCreate; }
    public String getArchitecture() { return architecture; }
    public CatalogManifest getCatalogManifest() { return catalogManifest; }
}
