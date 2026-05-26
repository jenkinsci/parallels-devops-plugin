package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A provisioned VM registered as a Jenkins agent. The agent is bootstrapped
 * via SSH using {@link PrlDevopsComputerLauncher}, which delegates to
 * {@link hudson.plugins.sshslaves.SSHLauncher} with configurable retry logic.
 */
public class PrlDevopsAgent extends AbstractCloudSlave {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(PrlDevopsAgent.class.getName());

    /**
     * Guards against double-deletion when both the retention strategy tearDown()
     * and the Jenkins _terminate() lifecycle callback fire for the same agent.
     * Transient so it resets to {@code false} on deserialization (restart scenario),
     * which is exactly what we want — an orphaned agent needs a fresh cleanup pass.
     */
    private transient final AtomicBoolean terminated = new AtomicBoolean(false);

    private final String cloudName;
    private final AgentTemplate template;
    private final String vmId;
    private final String vmIp;
    /** Epoch-millis when this node was first created (set once, never changes). */
    private final long provisionedAt;

    public PrlDevopsAgent(String cloudName, AgentTemplate template, String vmId, String vmIp)
            throws Descriptor.FormException, IOException {
        super("prl-" + vmId, template.getAgentWorkspaceDir(), new PrlDevopsComputerLauncher(vmIp, template));
        this.cloudName = cloudName;
        this.template = template;
        this.vmId = vmId;
        this.vmIp = vmIp;
        this.provisionedAt = System.currentTimeMillis();
        setNumExecutors(template.getNumExecutors());
        setLabelString(template.getTemplateLabel());
        setMode(Node.Mode.NORMAL);
        setRetentionStrategy(new PrlDevopsRetentionStrategy());
    }

    public String getCloudName() { return cloudName; }
    public AgentTemplate getTemplate() { return template; }
    public String getVmId() { return vmId; }
    public String getVmIp() { return vmIp; }
    public long getProvisionedAt() { return provisionedAt; }

    @Override
    public int getNumExecutors() {
        return template.getNumExecutors();
    }

    @Override
    public String getLabelString() {
        return template.getTemplateLabel();
    }

    @Override
    public PrlDevopsComputer createComputer() {
        return new PrlDevopsComputer(this);
    }

    /**
     * Atomically marks this agent as terminated.
     *
     * @return {@code true} if this call won the race and should perform cleanup;
     *         {@code false} if cleanup is already in progress or complete.
     */
    boolean markTerminated() {
        return terminated.compareAndSet(false, true);
    }

    /**
     * Called by Jenkins when the node is removed (UI delete, shutdown, {@link #cleanupOrphanedAgents}).
     *
     * <p>Deletes the backing VM via the API.  Uses the {@link #terminated} flag so that
     * when the retention strategy's {@code tearDown()} already called
     * {@link #markTerminated()} and {@code deleteVm()}, this method is a safe no-op.
     */
    @Override
    protected void _terminate(TaskListener listener) {
        if (!markTerminated()) {
            LOGGER.fine("[PrlDevops] _terminate skipped — already terminated for " + getNodeName());
            return;
        }
        listener.getLogger().println("[PrlDevopsAgent] Terminating VM " + vmId
                + " on node " + getNodeName());
        Jenkins jenkins = Jenkins.get();
        Cloud cloud = jenkins.clouds.getByName(cloudName);
        if (cloud instanceof PrlDevopsCloud prlCloud) {
            try {
                PrlDevopsApiClient client = prlCloud.buildApiClient();
                client.deleteVm(vmId);
                LOGGER.fine("[PrlDevops] Deleted VM " + vmId + " via _terminate on " + getNodeName());
            } catch (PrlApiException e) {
                LOGGER.log(Level.WARNING,
                        "[PrlDevops] Failed to delete VM " + vmId + " during _terminate: " + e.getMessage(), e);
            }
        } else {
            LOGGER.warning("[PrlDevops] Cloud '" + cloudName
                    + "' not found during _terminate — VM " + vmId + " may need manual cleanup.");
        }
    }

    /**
     * Startup hook: removes any {@link PrlDevopsAgent} nodes that are offline when
     * Jenkins starts (orphans left over from a crash or unclean shutdown).
     *
     * <p>Removing the node triggers {@link #_terminate}, which deletes the backing VM
     * via the API and deregisters the node from Jenkins.
     */
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void cleanupOrphanedAgents() {
        Jenkins jenkins = Jenkins.get();
        List<Node> nodes = new ArrayList<>(jenkins.getNodes());
        for (Node node : nodes) {
            if (node instanceof PrlDevopsAgent agent) {
                Computer c = agent.toComputer();
                if (c == null || c.isOffline()) {
                    LOGGER.info("[PrlDevops] Startup cleanup: removing orphaned agent "
                            + agent.getNodeName() + " (VM " + agent.getVmId() + ")");
                    try {
                        jenkins.removeNode(agent);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING,
                                "[PrlDevops] Failed to remove orphaned node " + agent.getNodeName(), e);
                    }
                }
            }
        }
    }
}
