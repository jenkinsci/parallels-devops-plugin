package com.parallels.jenkins.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Embedded catalog manifest for {@link CreateVmRequest}.
 *
 * <pre>
 * {
 *   "catalog_id":  "EMPTY-VM",
 *   "version":     "latest",
 *   "connection":  "host=user:password@https://catalog.example.com"
 * }
 * </pre>
 */
public class CatalogManifest {

    @JsonProperty("catalog_id")
    private final String catalogId;

    @JsonProperty("version")
    private final String version;

    @JsonProperty("connection")
    private final String connection;

    public CatalogManifest(String catalogId, String version, String connection) {
        this.catalogId = catalogId;
        this.version = version;
        this.connection = connection;
    }

    public String getCatalogId() { return catalogId; }
    public String getVersion() { return version; }
    public String getConnection() { return connection; }
}
