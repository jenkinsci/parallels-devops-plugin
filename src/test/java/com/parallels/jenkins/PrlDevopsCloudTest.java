package com.parallels.jenkins;

import com.parallels.jenkins.api.ConnectionMode;
import hudson.model.Label;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class PrlDevopsCloudTest {

    @Test
    void testConfigurationRoundTrip(JenkinsRule r) throws Exception {
        PrlDevopsCloud cloud = new PrlDevopsCloud("TestCloud");
        cloud.setServiceUrl("http://localhost:8080");
        cloud.setCredentialsId("test-credentials");
        cloud.setConnectionMode(ConnectionMode.HOST);
        cloud.setMaxAgents(10);

        r.jenkins.clouds.add(cloud);

        r.configRoundtrip();

        PrlDevopsCloud loaded = (PrlDevopsCloud) r.jenkins.clouds.getByName("TestCloud");
        assertNotNull(loaded);
        assertEquals("http://localhost:8080", loaded.getServiceUrl());
        assertEquals("test-credentials", loaded.getCredentialsId());
        assertEquals(ConnectionMode.HOST, loaded.getConnectionMode());
        assertEquals(10, loaded.getMaxAgents());
    }

    @Test
    void testAgentTemplateRoundTrip(JenkinsRule r) throws Exception {
        AgentTemplate template = new AgentTemplate("macos-sonoma");
        template.setProvisioningConfig(new CloneProvisioningConfig("macOS-Sonoma-base"));
        template.setNumExecutors(2);

        PrlDevopsCloud cloud = new PrlDevopsCloud("TemplateCloud");
        cloud.setServiceUrl("http://localhost:8080");
        cloud.setTemplates(Collections.singletonList(template));

        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        PrlDevopsCloud loaded = (PrlDevopsCloud) r.jenkins.clouds.getByName("TemplateCloud");
        assertNotNull(loaded);
        assertEquals(1, loaded.getTemplates().size());

        AgentTemplate loadedTemplate = loaded.getTemplates().get(0);
        assertEquals("macos-sonoma", loadedTemplate.getTemplateLabel());
        assertEquals("macOS-Sonoma-base", loadedTemplate.getBaseVmName());
        assertEquals(1, loadedTemplate.getNumExecutors());
    }

    @Test
    void testAgentTemplateMatchesLabel(JenkinsRule r) {
        AgentTemplate template = new AgentTemplate("macos-sonoma");
        template.setProvisioningConfig(new CloneProvisioningConfig("macOS-Sonoma-base"));

        assertTrue(template.matches(null));
        assertTrue(template.matches(Label.get("macos-sonoma")));
        assertFalse(template.matches(Label.get("ubuntu-22")));
    }

    @Test
    void testGetTemplateForLabel(JenkinsRule r) {
        AgentTemplate macTemplate = new AgentTemplate("macos-sonoma");
        macTemplate.setProvisioningConfig(new CloneProvisioningConfig("macOS-Sonoma-base"));
        AgentTemplate linuxTemplate = new AgentTemplate("ubuntu-22");
        linuxTemplate.setProvisioningConfig(new CloneProvisioningConfig("Ubuntu-22-base"));

        PrlDevopsCloud cloud = new PrlDevopsCloud("LabelCloud");
        List<AgentTemplate> templates = new ArrayList<>();
        templates.add(macTemplate);
        templates.add(linuxTemplate);
        cloud.setTemplates(templates);

        AgentTemplate found = cloud.getTemplateForLabel(Label.get("macos-sonoma"));
        assertNotNull(found);
        assertEquals("macos-sonoma", found.getTemplateLabel());

        assertNull(cloud.getTemplateForLabel(Label.get("windows-11")));
    }
}
