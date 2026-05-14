package com.parallels.jenkins;

import com.parallels.jenkins.api.ConnectionMode;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configuration-as-Code (CasC) round-trip tests.
 *
 * <p>Each test loads a YAML config via the CasC engine and asserts that the
 * resulting Jenkins objects have the expected field values. This verifies that
 * all {@code @DataBoundSetter} / {@code @DataBoundConstructor} bindings work
 * correctly with the CasC YAML schema.
 */
@WithJenkins
class PrlDevopsCascTest {

    /**
     * Clone-mode cloud: loads {@code casc-clone.yaml}, verifies the cloud, its
     * single template, and the {@link CloneProvisioningConfig}.
     */
    @Test
    void cascLoadsCloneCloud(JenkinsRule r) throws Exception {
        ConfigurationAsCode.get().configure(
                getClass().getResource("casc-clone.yaml").toString());

        PrlDevopsCloud cloud = (PrlDevopsCloud) r.jenkins.clouds.getByName("test-clone-cloud");
        assertNotNull(cloud, "Cloud 'test-clone-cloud' should be present");
        assertEquals("http://test-service.invalid:8080", cloud.getServiceUrl());
        assertEquals("test-credentials-id", cloud.getCredentialsId());
        assertEquals(ConnectionMode.HOST, cloud.getConnectionMode());
        assertEquals(5, cloud.getMaxAgents());

        assertEquals(1, cloud.getTemplates().size());
        AgentTemplate tmpl = cloud.getTemplates().get(0);
        assertEquals("test-label", tmpl.getTemplateLabel());
        assertEquals("test-user", tmpl.getVmUser());
        assertEquals("/tmp/test-workspace", tmpl.getAgentWorkspaceDir());
        assertEquals(1, tmpl.getNumExecutors());
        assertEquals(300, tmpl.getVmReadyTimeoutSeconds());
        assertEquals(10, tmpl.getVmReadyPollIntervalSeconds());

        assertInstanceOf(CloneProvisioningConfig.class, tmpl.getProvisioningConfig());
        CloneProvisioningConfig clone = (CloneProvisioningConfig) tmpl.getProvisioningConfig();
        assertEquals("test-base-vm", clone.getBaseVmName());
    }

    /**
     * Catalog-mode cloud: loads {@code casc-catalog.yaml}, verifies the cloud,
     * its single template, and the {@link CatalogProvisioningConfig}.
     */
    @Test
    void cascLoadsCatalogCloud(JenkinsRule r) throws Exception {
        ConfigurationAsCode.get().configure(
                getClass().getResource("casc-catalog.yaml").toString());

        PrlDevopsCloud cloud = (PrlDevopsCloud) r.jenkins.clouds.getByName("test-catalog-cloud");
        assertNotNull(cloud, "Cloud 'test-catalog-cloud' should be present");
        assertEquals("http://test-orchestrator.invalid:8080", cloud.getServiceUrl());
        assertEquals(ConnectionMode.ORCHESTRATOR, cloud.getConnectionMode());
        assertEquals(3, cloud.getMaxAgents());

        assertEquals(1, cloud.getTemplates().size());
        AgentTemplate tmpl = cloud.getTemplates().get(0);
        assertEquals("test-label", tmpl.getTemplateLabel());
        assertEquals("test-user", tmpl.getVmUser());
        assertEquals("/tmp/test-workspace", tmpl.getAgentWorkspaceDir());
        assertEquals(600, tmpl.getVmReadyTimeoutSeconds());
        assertEquals(15, tmpl.getVmReadyPollIntervalSeconds());

        assertInstanceOf(CatalogProvisioningConfig.class, tmpl.getProvisioningConfig());
        CatalogProvisioningConfig catalog = (CatalogProvisioningConfig) tmpl.getProvisioningConfig();
        assertEquals("test-catalog-id", catalog.getCatalogId());
        assertEquals("http://test-catalog.invalid", catalog.getCatalogUrl());
        assertEquals("x86_64", catalog.getArchitecture());
        assertEquals("latest", catalog.getCatalogVersion());
    }

    /**
     * Export round-trip: configure via CasC, export back to YAML, and verify
     * the export contains the expected cloud symbol — ensuring the
     * {@code @Symbol} annotations are wired correctly.
     */
    @Test
    void cascExportContainsCloudSymbol(JenkinsRule r) throws Exception {
        ConfigurationAsCode.get().configure(
                getClass().getResource("casc-clone.yaml").toString());

        // Export to string and verify our cloud symbol appears
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ConfigurationAsCode.get().export(out);
        String exported = out.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(exported.contains("parallelsDevops"), "Export should contain 'parallelsDevops' symbol");
        assertTrue(exported.contains("test-clone-cloud"), "Export should contain the cloud name");
    }
}
