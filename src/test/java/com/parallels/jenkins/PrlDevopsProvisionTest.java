package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.dto.CloneRequest;
import com.parallels.jenkins.api.dto.CloneResponse;
import com.parallels.jenkins.api.dto.CreateVmRequest;
import com.parallels.jenkins.api.dto.CreateVmResponse;
import com.parallels.jenkins.api.dto.VmStatusResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import com.parallels.jenkins.api.exception.PrlApiTimeoutException;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;

/**
 * JenkinsRule-based tests for {@link PrlDevopsCloud#provision} and
 * {@link PrlDevopsPlannedNode}.
 *
 * <p>A hand-rolled stub of {@link PrlDevopsApiClient} is injected via a
 * {@link TestableCloud} subclass that overrides {@link PrlDevopsCloud#buildApiClient()},
 * so no live Parallels DevOps server is required.
 */
public class PrlDevopsProvisionTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    // -------------------------------------------------------------------------
    // Stub API client
    // -------------------------------------------------------------------------

    private static class StubApiClient implements PrlDevopsApiClient {

        final String vmId;
        final String ip;
        volatile boolean deleteVmCalled = false;

        StubApiClient(String vmId, String ip) {
            this.vmId = vmId;
            this.ip = ip;
        }

        @Override
        public CloneResponse cloneVm(String sourceVmId, CloneRequest request) {
            CloneResponse resp = new CloneResponse();
            resp.setId(vmId);
            resp.setStatus("created");
            return resp;
        }

        @Override
        public void startVm(String id) {
            // no-op stub — VM is already in a startable state in tests
        }

        @Override
        public CreateVmResponse createVmFromCatalog(CreateVmRequest request) {
            CreateVmResponse resp = new CreateVmResponse();
            resp.setId(vmId);
            resp.setName(request.getName());
            resp.setCurrentState("stopped");
            return resp;
        }

        @Override
        public VmStatusResponse getVmStatus(String id) throws PrlApiException {
            VmStatusResponse resp = new VmStatusResponse();
            resp.setId(id);
            resp.setStatus("running");
            resp.setIpConfigured(ip);
            return resp;
        }

        @Override
        public void deleteVm(String id) {
            deleteVmCalled = true;
        }

        @Override
        public VmStatusResponse waitForVmReady(String id, String vmUser, Duration timeout, Duration interval)
                throws PrlApiException {
            return getVmStatus(id);
        }

        @Override
        public com.parallels.jenkins.api.dto.ExecuteResponse executeCommand(
                String id, com.parallels.jenkins.api.dto.ExecuteRequest request) {
            com.parallels.jenkins.api.dto.ExecuteResponse resp =
                    new com.parallels.jenkins.api.dto.ExecuteResponse();
            resp.setStdout("prl-ready");
            resp.setExitCode(0);
            return resp;
        }
    }

    // -------------------------------------------------------------------------
    // PrlDevopsCloud subclass with injectable API client
    // -------------------------------------------------------------------------

    private static class TestableCloud extends PrlDevopsCloud {

        private final PrlDevopsApiClient injectedClient;

        TestableCloud(String name, PrlDevopsApiClient client) {
            super(name);
            this.injectedClient = client;
        }

        @Override
        protected PrlDevopsApiClient buildApiClient() {
            return injectedClient;
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private TestableCloud buildCloud(String cloudName, PrlDevopsApiClient client, int maxAgents) {
        AgentTemplate template = new AgentTemplate("macos-sonoma", "macOS-Sonoma-base");
        template.setNumExecutors(1);
        template.setVmReadyTimeoutSeconds(10);
        template.setVmReadyPollIntervalSeconds(1);

        TestableCloud cloud = new TestableCloud(cloudName, client);
        cloud.setServiceUrl("http://localhost:9999");
        cloud.setCredentialsId("dummy-cred");
        cloud.setMaxAgents(maxAgents);

        List<AgentTemplate> templates = new ArrayList<>();
        templates.add(template);
        cloud.setTemplates(templates);
        return cloud;
    }

    // -------------------------------------------------------------------------
    // Tests — happy path
    // -------------------------------------------------------------------------

    @Test
    public void provision_returnsOneNode_forMatchingLabelAndExcessWorkloadOne() throws Exception {
        StubApiClient stub = new StubApiClient("vm-abc-123", "192.168.1.42");
        TestableCloud cloud = buildCloud("PrlCloud", stub, 5);

        Collection<NodeProvisioner.PlannedNode> nodes =
                cloud.provision(new Cloud.CloudState(Label.get("macos-sonoma"), 1), 1);

        assertEquals(1, nodes.size());
        NodeProvisioner.PlannedNode planned = nodes.iterator().next();
        assertEquals("prl-vm-abc-123", planned.displayName);
        assertEquals(1, planned.numExecutors);
    }

    @Test
    public void plannedNode_future_resolvesToPrlDevopsSlave() throws Exception {
        StubApiClient stub = new StubApiClient("vm-def-456", "10.0.0.55");
        TestableCloud cloud = buildCloud("PrlCloud2", stub, 5);

        Collection<NodeProvisioner.PlannedNode> nodes =
                cloud.provision(new Cloud.CloudState(Label.get("macos-sonoma"), 1), 1);

        NodeProvisioner.PlannedNode planned = nodes.iterator().next();
        Node node = planned.future.get(); // blocks until async future completes

        assertNotNull(node);
        assertTrue("Expected PrlDevopsSlave, got: " + node.getClass(), node instanceof PrlDevopsSlave);

        PrlDevopsSlave slave = (PrlDevopsSlave) node;
        assertEquals("prl-vm-def-456", slave.getNodeName());
        assertEquals("vm-def-456", slave.getVmId());
        assertEquals("PrlCloud2", slave.getCloudName());
    }

    @Test
    public void provision_returnsMultipleNodes_whenExcessWorkloadIsGreaterThanOne() throws Exception {
        // Each cloneVm call must return a unique VM ID; our stub always returns the same ID.
        // Override cloneVm to generate distinct IDs per call.
        StubApiClient stub = new StubApiClient("vm-multi", "10.1.2.3") {
            private int counter = 0;

            @Override
            public CloneResponse cloneVm(String sourceVmId, CloneRequest request) {
                CloneResponse r = new CloneResponse();
                r.setId("vm-multi-" + (++counter));
                r.setStatus("created");
                return r;
            }

            @Override
            public VmStatusResponse getVmStatus(String id) throws PrlApiException {
                VmStatusResponse r = new VmStatusResponse();
                r.setId(id);
                r.setStatus("running");
                r.setIpConfigured("10.1.2.3");
                return r;
            }
        };

        TestableCloud cloud = buildCloud("PrlCloud3", stub, 5);

        Collection<NodeProvisioner.PlannedNode> nodes =
                cloud.provision(new Cloud.CloudState(Label.get("macos-sonoma"), 1), 3);

        assertEquals(3, nodes.size());
    }

    // -------------------------------------------------------------------------
    // Tests — maxAgents cap
    // -------------------------------------------------------------------------

    /**
     * maxAgents=0 is the default (unset) value and means unlimited provisioning.
     */
    @Test
    public void provision_treatsMaxAgentsZeroAsUnlimited() {
        StubApiClient stub = new StubApiClient("vm-unlim", "1.2.3.4") {
            private int counter = 0;

            @Override
            public CloneResponse cloneVm(String sourceVmId, CloneRequest request) {
                CloneResponse resp = new CloneResponse();
                resp.setId("vm-unlim-" + (++counter));
                resp.setStatus("created");
                return resp;
            }
        };
        TestableCloud cloud = buildCloud("PrlUnlimCloud", stub, 0); // 0 → unlimited

        Collection<NodeProvisioner.PlannedNode> nodes =
                cloud.provision(new Cloud.CloudState(Label.get("macos-sonoma"), 1), 2);

        // With maxAgents=0 (unlimited) and excessWorkload=2, 2 nodes should be returned.
        assertEquals(2, nodes.size());
    }

    @Test
    public void provision_capsToMaxAgents_whenBudgetIsLessThanExcessWorkload() {
        StubApiClient stub = new StubApiClient("vm-ltd", "1.2.3.5") {
            private int counter = 0;

            @Override
            public CloneResponse cloneVm(String sourceVmId, CloneRequest request) {
                CloneResponse r = new CloneResponse();
                r.setId("vm-ltd-" + (++counter));
                r.setStatus("created");
                return r;
            }
        };

        TestableCloud cloud = buildCloud("PrlLtdCloud", stub, 2); // maxAgents = 2

        // excessWorkload = 5, but maxAgents = 2 → expect only 2 planned nodes
        Collection<NodeProvisioner.PlannedNode> nodes =
                cloud.provision(new Cloud.CloudState(Label.get("macos-sonoma"), 1), 5);

        assertEquals(2, nodes.size());
    }

    // -------------------------------------------------------------------------
    // Tests — no matching template
    // -------------------------------------------------------------------------

    @Test
    public void provision_returnsEmpty_forNonMatchingLabel() {
        StubApiClient stub = new StubApiClient("vm-no", "0.0.0.0");
        TestableCloud cloud = buildCloud("PrlNoMatch", stub, 5);

        Collection<NodeProvisioner.PlannedNode> nodes =
                cloud.provision(new Cloud.CloudState(Label.get("windows-11"), 1), 1);

        assertTrue(nodes.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Tests — failure + cleanup
    // -------------------------------------------------------------------------

    @Test
    public void plannedNode_future_throwsAndDeletesVm_whenWaitForReadyTimesOut() throws Exception {
        StubApiClient stub = new StubApiClient("vm-timeout", "0.0.0.0") {
            @Override
            public VmStatusResponse waitForVmReady(String id, String vmUser, Duration timeout, Duration interval)
                    throws PrlApiException {
                throw new PrlApiTimeoutException(id, timeout);
            }
        };

        TestableCloud cloud = buildCloud("PrlTimeoutCloud", stub, 5);

        Collection<NodeProvisioner.PlannedNode> nodes =
                cloud.provision(new Cloud.CloudState(Label.get("macos-sonoma"), 1), 1);

        assertEquals(1, nodes.size());

        try {
            nodes.iterator().next().future.get();
            fail("Expected ExecutionException to be thrown");
        } catch (ExecutionException e) {
            assertTrue("Cause should be PrlApiTimeoutException",
                    e.getCause() instanceof PrlApiTimeoutException);
        }

        // Give the cleanup task a moment to run inside the future's catch block.
        Thread.sleep(200);
        assertTrue("deleteVm() should have been called after failure", stub.deleteVmCalled);
    }

    // -------------------------------------------------------------------------
    // Tests — canProvision
    // -------------------------------------------------------------------------

    @Test
    public void canProvision_returnsTrueForConfiguredCloudAndMatchingLabel() {
        TestableCloud cloud = buildCloud("PrlCanProv", new StubApiClient("v", "i"), 5);

        assertTrue(cloud.canProvision(new Cloud.CloudState(Label.get("macos-sonoma"), 1)));
    }

    @Test
    public void canProvision_returnsFalseForUnknownLabel() {
        TestableCloud cloud = buildCloud("PrlCantProv", new StubApiClient("v", "i"), 5);

        assertFalse(cloud.canProvision(new Cloud.CloudState(Label.get("unknown-os"), 1)));
    }
}
