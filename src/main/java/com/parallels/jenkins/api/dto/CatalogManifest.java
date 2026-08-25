package com.parallels.jenkins.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Embedded catalog manifest for {@link CreateVmRequest}.
 *
 * <pre>
 * {
 *   "catalog_id":   "EMPTY-VM",
 *   "version":      "latest",
 *   "connection":   "host=user:password@https://catalog.example.com",
 *   "machine_name": "jenkins-clone-1234",
 *   "architecture": "arm64"
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CatalogManifest {

    @JsonProperty("catalog_id")
    private final String catalogId;

    @JsonProperty("version")
    private final String version;

    @JsonProperty("connection")
    private final String connection;

    @JsonProperty("catalog_manager_id")
    private final String catalogManagerId;

    @JsonProperty("machine_name")
    private final String machineName;

    @JsonProperty("architecture")
    private final String architecture;

    public CatalogManifest(String catalogId, String version, String connection,
                           String machineName, String architecture) {
        this(catalogId, version, connection, null, machineName, architecture);
    }

    public CatalogManifest(String catalogId, String version, String connection,
                           String catalogManagerId, String machineName, String architecture) {
        this.catalogId = catalogId;
        this.version = version;
        this.connection = connection;
        this.catalogManagerId = catalogManagerId;
        this.machineName = machineName;
        this.architecture = architecture;
    }

    public String getCatalogId() { return catalogId; }
    public String getVersion() { return version; }
    public String getConnection() { return connection; }
    public String getCatalogManagerId() { return catalogManagerId; }
    public String getMachineName() { return machineName; }
    public String getArchitecture() { return architecture; }
}
