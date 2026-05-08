package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.dto.VmStatusResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.slaves.NodeProvisioner;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link NodeProvisioner.PlannedNode} that asynchronously waits for a cloned VM
 * to reach the {@code running} state before registering it as a Jenkins agent.
 *
 * <p>The underlying {@link Future} is submitted to the provided {@link ExecutorService}
 * (typically {@code hudson.model.Computer.threadPoolForRemoting}) so that the
 * ready-wait loop never blocks the calling (provisioner) thread.
 *
 * <p>On success the future returns a fully constructed {@link PrlDevopsSlave}.
 * On timeout or API error the future logs the failure, requests deletion of the
 * orphaned VM, and re-throws so Jenkins cancels the planned node.
 */
public class PrlDevopsPlannedNode extends NodeProvisioner.PlannedNode {

    private static final Logger LOGGER = Logger.getLogger(PrlDevopsPlannedNode.class.getName());

    /**
     * @param cloudName   Name of the owning {@link PrlDevopsCloud} (stored on the slave for counting).
     * @param template    The {@link AgentTemplate} used to clone this VM.
     * @param vmId        ID of the newly cloned VM returned by the API.
     * @param apiClient   API client used to poll status and clean up on failure.
     * @param timeout     Maximum time to wait for the VM to reach {@code running}.
     * @param pollInterval  Time between polling attempts.
     * @param startOnCreate When {@code true} the VM was created with {@code startOnCreate=true}
     *                      and is already booting — {@code startVm()} must NOT be called again.
     *                      When {@code false} (clone mode) the VM is stopped after cloning and
     *                      must be explicitly started.
     * @param executor      Thread pool on which the wait loop is executed.
     */
    public PrlDevopsPlannedNode(String cloudName,
                                AgentTemplate template,
                                String vmId,
                                PrlDevopsApiClient apiClient,
                                Duration timeout,
                                Duration pollInterval,
                                boolean startOnCreate,
                                ExecutorService executor) {
        super(
                "prl-" + vmId,
                submitAsync(cloudName, template, vmId, apiClient, timeout, pollInterval,
                        startOnCreate, executor),
                template.getNumExecutors()
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Future<Node> submitAsync(String cloudName,
                                            AgentTemplate template,
                                            String vmId,
                                            PrlDevopsApiClient apiClient,
                                            Duration timeout,
                                            Duration pollInterval,
                                            boolean startOnCreate,
                                            ExecutorService executor) {
        Callable<Node> task = () -> {
            if (startOnCreate) {
                LOGGER.info("[PrlDevops] VM " + vmId
                        + " was created with startOnCreate=true — skipping startVm()");
            } else {
                LOGGER.info("[PrlDevops] Starting VM " + vmId);
                try {
                    apiClient.startVm(vmId);
                } catch (PrlApiException e) {
                    LOGGER.log(Level.WARNING,
                            "[PrlDevops] startVm() failed for VM " + vmId + ": " + e.getMessage(), e);
                    try { apiClient.deleteVm(vmId); } catch (PrlApiException ignored) { }
                    throw e;
                }
            }
            LOGGER.info("[PrlDevops] Waiting for VM " + vmId + " to become ready"
                    + " (timeout=" + timeout + ", interval=" + pollInterval + ")");
            try {
                VmStatusResponse status = apiClient.waitForVmReady(vmId, template.getVmUser(), timeout, pollInterval);
                String vmIp = status.getIpConfigured();
                if (vmIp == null || vmIp.isBlank() || vmIp.equals("-")) {
                    throw new PrlApiException(
                            "VM " + vmId + " is running but ip_configured is '" + vmIp
                            + "' — cannot SSH. Check that Parallels Tools are installed in the VM.");
                }
                LOGGER.info("[PrlDevops] VM " + vmId + " is running at " + vmIp + " — registering agent.");
                return new PrlDevopsSlave(cloudName, template, vmId, vmIp);
            } catch (PrlApiException | Descriptor.FormException | IOException e) {
                LOGGER.log(Level.WARNING,
                        "[PrlDevops] VM " + vmId + " failed to become ready; cleaning up. " + e.getMessage(), e);
                try {
                    apiClient.deleteVm(vmId);
                    LOGGER.info("[PrlDevops] Successfully deleted orphaned VM " + vmId);
                } catch (PrlApiException cleanupEx) {
                    LOGGER.log(Level.WARNING,
                            "[PrlDevops] Could not delete orphaned VM " + vmId + ": " + cleanupEx.getMessage(),
                            cleanupEx);
                }
                throw e;
            }
        };
        return executor.submit(task);
    }
}
