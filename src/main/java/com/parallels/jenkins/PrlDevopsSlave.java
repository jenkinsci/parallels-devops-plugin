package com.parallels.jenkins;

import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.RetentionStrategy;

import java.io.IOException;

/**
 * A provisioned VM registered as a Jenkins agent. The agent is bootstrapped
 * via SSH — Jenkins connects to the VM's IP, copies agent.jar, and starts it.
 */
public class PrlDevopsSlave extends AbstractCloudSlave {

    private static final long serialVersionUID = 1L;

    private final String cloudName;
    private final AgentTemplate template;
    private final String vmId;
    private final String vmIp;
    /** Epoch-millis when this node was first created (set once, never changes). */
    private final long provisionedAt;

    public PrlDevopsSlave(String cloudName, AgentTemplate template, String vmId, String vmIp)
            throws Descriptor.FormException, IOException {
        super(
                "prl-" + vmId,
                "/tmp/jenkins-agent",
                new SSHLauncher(vmIp, 22, template.getSshCredentialsId())
        );
        this.cloudName = cloudName;
        this.template = template;
        this.vmId = vmId;
        this.vmIp = vmIp;
        this.provisionedAt = System.currentTimeMillis();
        setNumExecutors(template.getNumExecutors());
        setLabelString(template.getTemplateLabel());
        setMode(Node.Mode.NORMAL);
        setRetentionStrategy(RetentionStrategy.NOOP);
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
    public AbstractCloudComputer<PrlDevopsSlave> createComputer() {
        return new AbstractCloudComputer<>(this);
    }

    @Override
    protected void _terminate(TaskListener listener) {
        listener.getLogger().println(
                "[PrlDevopsSlave] terminate() called for VM " + vmId);
    }
}
