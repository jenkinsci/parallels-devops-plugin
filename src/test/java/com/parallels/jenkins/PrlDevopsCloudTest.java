package com.parallels.jenkins;

import hudson.model.Label;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

import static org.junit.Assert.*;

public class PrlDevopsCloudTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void testConfigurationRoundTrip() throws Exception {
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
    public void testAgentTemplateRoundTrip() throws Exception {
        AgentTemplate template = new AgentTemplate(
                "macos-sonoma",
                "macOS-Sonoma-base"
        );
        template.setNumExecutors(2);

        PrlDevopsCloud cloud = new PrlDevopsCloud("TemplateCloud");
        cloud.setServiceUrl("http://localhost:8080");
        cloud.setTemplates(Collections.singletonList(template));

        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();

        PrlDevopsCloud loaded = (PrlDevopsCloud) r.jenkins.clouds.getByName("TemplateCloud");
        assertNotNull(loaded);
        assertEquals(1, loaded.getTemplates().size());

        AgentTemplate loaded_t = loaded.getTemplates().get(0);
        assertEquals("macos-sonoma", loaded_t.getTemplateLabel());
        assertEquals("macOS-Sonoma-base", loaded_t.getBaseVmName());
        assertEquals(2, loaded_t.getNumExecutors());
    }

    @Test
    public void testAgentTemplateMatchesLabel() {
        AgentTemplate template = new AgentTemplate(
                "macos-sonoma",
                "macOS-Sonoma-base"
        );

        assertTrue(template.matches(null));
        assertTrue(template.matches(Label.get("macos-sonoma")));
        assertFalse(template.matches(Label.get("ubuntu-22")));
    }

    @Test
    public void testGetTemplateForLabel() {
        AgentTemplate macTemplate = new AgentTemplate("macos-sonoma", "macOS-Sonoma-base");
        AgentTemplate linuxTemplate = new AgentTemplate("ubuntu-22", "Ubuntu-22-base");

        PrlDevopsCloud cloud = new PrlDevopsCloud("LabelCloud");
        java.util.List<AgentTemplate> templates = new java.util.ArrayList<>();
        templates.add(macTemplate);
        templates.add(linuxTemplate);
        cloud.setTemplates(templates);

        AgentTemplate found = cloud.getTemplateForLabel(Label.get("macos-sonoma"));
        assertNotNull(found);
        assertEquals("macos-sonoma", found.getTemplateLabel());

        assertNull(cloud.getTemplateForLabel(Label.get("windows-11")));
    }
}
