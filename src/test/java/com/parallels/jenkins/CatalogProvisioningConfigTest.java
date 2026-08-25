package com.parallels.jenkins;

import com.parallels.jenkins.api.dto.CatalogManifest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CatalogProvisioningConfigTest {

    @Test
    void testCatalogConfigDefaultsAndCanProvision() {
        CatalogProvisioningConfig config = new CatalogProvisioningConfig("my-catalog");
        assertEquals("my-catalog", config.getCatalogId());
        assertEquals("latest", config.getCatalogVersion());
        assertEquals("arm64", config.getArchitecture());
        assertTrue(config.canProvision());

        CatalogProvisioningConfig emptyConfig = new CatalogProvisioningConfig("");
        assertFalse(emptyConfig.canProvision());
    }

    @Test
    void testCatalogManagerAndVersionSetters() {
        CatalogProvisioningConfig config = new CatalogProvisioningConfig("ubuntu-22.04");
        config.setCatalogVersion("1.0.0");
        config.setCatalogManagerId("cat-mgr-1234");
        config.setArchitecture("x86_64");

        assertEquals("ubuntu-22.04", config.getCatalogId());
        assertEquals("1.0.0", config.getCatalogVersion());
        assertEquals("cat-mgr-1234", config.getCatalogManagerId());
        assertEquals("x86_64", config.getArchitecture());
    }

    @Test
    void testCatalogManifestDtoConstructors() {
        CatalogManifest manifestWithMgr = new CatalogManifest("my-catalog", "1.0.0", null, "cat-mgr-999", "machine-1", "arm64");
        assertEquals("my-catalog", manifestWithMgr.getCatalogId());
        assertEquals("1.0.0", manifestWithMgr.getVersion());
        assertNull(manifestWithMgr.getConnection());
        assertEquals("cat-mgr-999", manifestWithMgr.getCatalogManagerId());
        assertEquals("machine-1", manifestWithMgr.getMachineName());
        assertEquals("arm64", manifestWithMgr.getArchitecture());

        CatalogManifest manifestWithConn = new CatalogManifest("my-catalog", "latest", "host=u:p@https://cat.com", "machine-2", "x86_64");
        assertEquals("host=u:p@https://cat.com", manifestWithConn.getConnection());
        assertNull(manifestWithConn.getCatalogManagerId());
    }

    @Test
    void testCatalogManifestJsonSerialization_omitsNullFields() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // Case 1: Both connection and catalogManagerId are null -> both omitted
        CatalogManifest manifestBothNull = new CatalogManifest("my-catalog", "latest", null, null, "machine-1", "arm64");
        String jsonBothNull = mapper.writeValueAsString(manifestBothNull);
        assertFalse(jsonBothNull.contains("connection"), "JSON should omit 'connection' field when null");
        assertFalse(jsonBothNull.contains("catalog_manager_id"), "JSON should omit 'catalog_manager_id' field when null");

        // Case 2: catalogManagerId provided, connection null -> connection omitted, catalog_manager_id included
        CatalogManifest manifestMgrOnly = new CatalogManifest("my-catalog", "latest", null, "cat-mgr-123", "machine-2", "arm64");
        String jsonMgrOnly = mapper.writeValueAsString(manifestMgrOnly);
        assertFalse(jsonMgrOnly.contains("connection"), "JSON should omit 'connection' field when null");
        assertTrue(jsonMgrOnly.contains("\"catalog_manager_id\":\"cat-mgr-123\""), "JSON should include 'catalog_manager_id'");

        // Case 3: connection provided, catalogManagerId null -> catalog_manager_id omitted, connection included
        CatalogManifest manifestConnOnly = new CatalogManifest("my-catalog", "latest", "host=u:p@url", null, "machine-3", "arm64");
        String jsonConnOnly = mapper.writeValueAsString(manifestConnOnly);
        assertTrue(jsonConnOnly.contains("\"connection\":\"host=u:p@url\""), "JSON should include 'connection'");
        assertFalse(jsonConnOnly.contains("catalog_manager_id"), "JSON should omit 'catalog_manager_id' field when null");
    }
}
