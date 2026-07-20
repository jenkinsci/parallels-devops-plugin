package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsApiClient;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * A provisioned VM registered as a Jenkins agent. The agent is bootstrapped
 * using an inbound agent connection via {@link PrlDevopsComputerLauncher}, which
 * uses the Parallels DevOps Service execute API to download and start agent.jar.
 * The agent then connects TO the Jenkins controller (not SSH-based).
 */
public class PrlDevopsAgent extends AbstractCloudSlave {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(PrlDevopsAgent.class.getName());

    private final String cloudName;
    private final AgentTemplate template;
    private final String vmId;
    /** Kept for backward compatibility, but no longer used for agent connection. */
    private final String vmIp;
    /** Epoch-millis when this node was first created (set once, never changes). */
    private final long provisionedAt;

    public PrlDevopsAgent(String cloudName, AgentTemplate template, String vmId, String vmIp,
                          PrlDevopsApiClient apiClient)
            throws Descriptor.FormException, IOException {
        super("prl-" + vmId, template.getAgentWorkspaceDir(), 
              new PrlDevopsComputerLauncher(cloudName, vmId, template.getVmUser(), apiClient, template));
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
    
    /**
     * Looks up the cloud that provisioned this agent and returns its API client.
     * Used by retention strategy and other components that need to interact with the VM.
     */
    public PrlDevopsApiClient getApiClient() throws IOException {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            throw new IOException("[PrlDevops] Jenkins instance not available");
        }
        
        Cloud cloud = jenkins.getCloud(cloudName);
        if (!(cloud instanceof PrlDevopsCloud prlCloud)) {
            throw new IOException("[PrlDevops] Cloud '" + cloudName + "' not found or not a PrlDevopsCloud");
        }
        
        try {
            return prlCloud.buildApiClient();
        } catch (com.parallels.jenkins.api.exception.PrlApiException e) {
            throw new IOException("[PrlDevops] Failed to build API client for cloud: '" + cloudName + "'", e);
        }
    }

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

    @Override
    protected void _terminate(TaskListener listener) {
        listener.getLogger().println("[PrlDevopsAgent] terminate() called for VM " + vmId);
        LOGGER.fine("[PrlDevops] _terminate invoked for " + getNodeName());
    }
}
