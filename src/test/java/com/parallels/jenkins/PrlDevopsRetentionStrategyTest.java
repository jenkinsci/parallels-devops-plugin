package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PrlDevopsRetentionStrategyTest {

    @Test
    void launcherUsesTemplateSettings() {
        AgentTemplate template = new AgentTemplate("test-label");
        template.setVmUser("testuser");
        template.setJavaPath("/usr/bin/java");
        template.setJvmOptions("-Xmx512m");
        template.setAgentConnectionTimeoutSec(600);

        // Create a stub client (won't be used in this test)
        PrlDevopsHttpClient stubClient = new PrlDevopsHttpClient.Builder()
                .baseUrl("http://localhost:8080")
                .bearerToken("test-token")
                .build();

        PrlDevopsComputerLauncher launcher = new PrlDevopsComputerLauncher(
                "test-cloud", "vm-12345", "testuser", stubClient, template);

        assertEquals("vm-12345", launcher.getVmId());
        assertEquals("testuser", launcher.getVmUser());
        assertEquals(600, launcher.getAgentConnectionTimeoutSec());
        assertFalse(launcher.hasLaunchFailed());
    }

    @Test
    void launcherConfiguresConnectionTimeout() {
        AgentTemplate template = new AgentTemplate("test-label");
        template.setAgentConnectionTimeoutSec(300);

        PrlDevopsHttpClient stubClient = new PrlDevopsHttpClient.Builder()
                .baseUrl("http://localhost:8080")
                .bearerToken("test-token")
                .build();

        PrlDevopsComputerLauncher launcher = new PrlDevopsComputerLauncher(
                "test-cloud", "vm-99999", "parallels", stubClient, template);

        assertEquals(300, launcher.getAgentConnectionTimeoutSec());
    }

    @Test
    void retentionStrategyIsConstructable() {
        assertNotNull(new PrlDevopsRetentionStrategy());
    }
}
