package com.parallels.jenkins;

import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy;
import hudson.slaves.AbstractCloudSlave;

import java.io.IOException;

/**
 * A provisioned VM registered as a Jenkins agent. The agent is bootstrapped
 * via SSH — Jenkins connects to the VM's IP, copies agent.jar, and starts it.
 */
public class PrlDevopsAgent extends AbstractCloudSlave {

    private static final long serialVersionUID = 1L;

    private final String cloudName;
    private final AgentTemplate template;
    private final String vmId;
    private final String vmIp;
    /** Epoch-millis when this node was first created (set once, never changes). */
    private final long provisionedAt;

    public PrlDevopsAgent(String cloudName, AgentTemplate template, String vmId, String vmIp)
            throws Descriptor.FormException, IOException {
        super(
                "prl-" + vmId,
                template.getAgentWorkspaceDir(),
                buildLauncher(vmIp, template)
        );
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

    /**
     * Builds the SSH launcher for a freshly provisioned, ephemeral VM.
     *
     * <ul>
     *   <li>{@link NonVerifyingKeyVerificationStrategy} — ephemeral VMs have unknown host
     *       keys; verifying them causes an instant connection failure.</li>
     *   <li>20 retries / 15 s wait — gives the SSH service inside the VM time to
     *       become reachable after the OS reports the VM as running.</li>
     * </ul>
     */
    private static SSHLauncher buildLauncher(String vmIp, AgentTemplate template) {
        SSHLauncher launcher = new SSHLauncher(vmIp, 22, template.getSshCredentialsId());
        launcher.setSshHostKeyVerificationStrategy(new NonVerifyingKeyVerificationStrategy());
        launcher.setMaxNumRetries(20);
        launcher.setRetryWaitTime(15);
        return launcher;
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

    @Override
    protected void _terminate(TaskListener listener) {
        listener.getLogger().println(
                "[PrlDevopsAgent] terminate() called for VM " + vmId);
    }
}
