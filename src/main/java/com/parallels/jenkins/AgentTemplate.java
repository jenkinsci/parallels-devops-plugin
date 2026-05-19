package com.parallels.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

import java.io.Serializable;
import java.util.Collections;

/**
 * Per-VM-type configuration. One {@code AgentTemplate} maps to one VM type in
 * Parallels DevOps Service. The provisioning strategy (clone vs. catalog) is
 * expressed as a {@link ProvisioningConfig} Describable, rendered via
 * {@code <f:dropdownDescriptorSelector>} with no inline JavaScript.
 */
public class AgentTemplate extends AbstractDescribableImpl<AgentTemplate> implements Serializable {

    private static final String DEFAULT_AGENT_WORKSPACE_DIR = "/tmp/jenkins-agent";
    private static final String DEFAULT_VM_USER = "";
    private static final int ONE_SHOT_EXECUTORS = 1;

    private static final long serialVersionUID = 1L;

    private final String templateLabel;
    /** OS user account used to run commands on the VM via the execute API. */
    private String vmUser = DEFAULT_VM_USER;
    /** Jenkins credentials ID for SSH agent bootstrap (username + password or key). */
    private String sshCredentialsId;
    /** SSH port on the cloned VM (default 22). */
    private int sshPort = 22;
    /** Path to Java on the agent VM (default: {@code java}). */
    private String javaPath = "java";
    /** Extra JVM flags passed to the remoting process. */
    private String jvmOptions = "";
    /** Maximum SSH connection attempts before marking the node offline (default 5). */
    private int sshRetries = 5;
    /** Seconds to wait between SSH retry attempts (default 15). */
    private int sshRetryDelaySec = 15;
    /**
     * Filesystem path used as the Jenkins agent workspace on the provisioned VM.
     * Defaults to {@code /tmp/jenkins-agent} for compatibility with existing test images.
     * Override with a path that suits your VM image.
     */
    private String agentWorkspaceDir = DEFAULT_AGENT_WORKSPACE_DIR;
    private int numExecutors = ONE_SHOT_EXECUTORS;
    private int vmReadyTimeoutSeconds = 600;  // 10 min — covers VM boot + SSH readiness after creation
    private int vmReadyPollIntervalSeconds = 10;

    /**
     * Legacy field kept solely for XStream migration of configs saved before the
     * {@link ProvisioningConfig} refactor. XStream deserializes it from old XML;
     * {@link #readResolve()} promotes it into a {@link CloneProvisioningConfig}.
     */
    @Deprecated
    private String baseVmName;

    /**
     * Encapsulates all provisioning-strategy-specific fields (e.g. base VM name
     * for clone mode, catalog ID/URL for catalog mode).
     */
    private ProvisioningConfig provisioningConfig;

    @DataBoundConstructor
    public AgentTemplate(String templateLabel) {
        this.templateLabel = templateLabel;
    }

    public String getTemplateLabel() { return templateLabel; }
    public String getVmUser() { return vmUser; }
    public String getSshCredentialsId() { return sshCredentialsId; }
    public int getSshPort() { return sshPort; }
    public String getJavaPath() { return javaPath; }
    public String getJvmOptions() { return jvmOptions; }
    public int getSshRetries() { return sshRetries; }
    public int getSshRetryDelaySec() { return sshRetryDelaySec; }
    public String getAgentWorkspaceDir() { return agentWorkspaceDir; }
    public int getNumExecutors() { return ONE_SHOT_EXECUTORS; }
    public int getVmReadyTimeoutSeconds() { return vmReadyTimeoutSeconds; }
    public int getVmReadyPollIntervalSeconds() { return vmReadyPollIntervalSeconds; }
    public ProvisioningConfig getProvisioningConfig() { return provisioningConfig; }

    public boolean canProvision() {
        return provisioningConfig != null && provisioningConfig.canProvision();
    }

    public PrlDevopsPlannedNode provision(String cloudName,
                                          Label label,
                                          com.parallels.jenkins.api.PrlDevopsApiClient apiClient,
                                          java.time.Duration timeout,
                                          java.time.Duration pollInterval,
                                          java.util.concurrent.ExecutorService executor)
            throws com.parallels.jenkins.api.exception.PrlApiException {
        if (provisioningConfig == null) {
            throw new com.parallels.jenkins.api.exception.PrlApiException(
                    "No provisioning mode is configured for template '" + templateLabel + "'.");
        }
        return provisioningConfig.provision(cloudName, this, label, apiClient, timeout, pollInterval, executor);
    }

    /**
     * XStream deserialization hook. Migrates old configs that stored
     * {@code baseVmName} directly on this class (before the
     * {@link ProvisioningConfig} refactor) into a {@link CloneProvisioningConfig}.
     * Also guards against {@code null} for configs that predate both fields.
     */
    protected Object readResolve() {
        if (provisioningConfig == null) {
            //noinspection deprecation
            provisioningConfig = new CloneProvisioningConfig(baseVmName != null ? baseVmName : "");
        }
        if (agentWorkspaceDir == null || agentWorkspaceDir.isBlank()) {
            agentWorkspaceDir = DEFAULT_AGENT_WORKSPACE_DIR;
        }
        if (vmUser == null) {
            vmUser = DEFAULT_VM_USER;
        }
        if (sshPort <= 0) {
            sshPort = 22;
        }
        if (javaPath == null || javaPath.isBlank()) {
            javaPath = "java";
        }
        if (jvmOptions == null) {
            jvmOptions = "";
        }
        if (sshRetries <= 0) {
            sshRetries = 5;
        }
        if (sshRetryDelaySec <= 0) {
            sshRetryDelaySec = 15;
        }
        numExecutors = ONE_SHOT_EXECUTORS;
        return this;
    }

    public String getBaseVmName() {
        return provisioningConfig instanceof CloneProvisioningConfig c ? c.getBaseVmName() : null;
    }

    public String getArchitecture() {
        return provisioningConfig instanceof CatalogProvisioningConfig c ? c.getArchitecture() : "arm64";
    }

    public String getCatalogId() {
        return provisioningConfig instanceof CatalogProvisioningConfig c ? c.getCatalogId() : null;
    }

    public String getCatalogVersion() {
        return provisioningConfig instanceof CatalogProvisioningConfig c ? c.getCatalogVersion() : "latest";
    }

    public String getCatalogUrl() {
        return provisioningConfig instanceof CatalogProvisioningConfig c ? c.getCatalogUrl() : null;
    }

    public String getCatalogCredentialsId() {
        return provisioningConfig instanceof CatalogProvisioningConfig c ? c.getCatalogCredentialsId() : null;
    }

    @DataBoundSetter
    public void setVmUser(String vmUser) {
        this.vmUser = vmUser != null ? vmUser.trim() : DEFAULT_VM_USER;
    }

    @DataBoundSetter
    public void setSshCredentialsId(String sshCredentialsId) {
        this.sshCredentialsId = sshCredentialsId;
    }

    @DataBoundSetter
    public void setSshPort(int sshPort) {
        this.sshPort = sshPort > 0 ? sshPort : 22;
    }

    @DataBoundSetter
    public void setJavaPath(String javaPath) {
        this.javaPath = (javaPath != null && !javaPath.isBlank()) ? javaPath : "java";
    }

    @DataBoundSetter
    public void setJvmOptions(String jvmOptions) {
        this.jvmOptions = jvmOptions != null ? jvmOptions : "";
    }

    @DataBoundSetter
    public void setSshRetries(int sshRetries) {
        this.sshRetries = sshRetries > 0 ? sshRetries : 5;
    }

    @DataBoundSetter
    public void setSshRetryDelaySec(int sshRetryDelaySec) {
        this.sshRetryDelaySec = sshRetryDelaySec > 0 ? sshRetryDelaySec : 15;
    }

    @DataBoundSetter
    public void setAgentWorkspaceDir(String agentWorkspaceDir) {
        this.agentWorkspaceDir = (agentWorkspaceDir != null && !agentWorkspaceDir.isBlank())
                ? agentWorkspaceDir : DEFAULT_AGENT_WORKSPACE_DIR;
    }

    @DataBoundSetter
    public void setNumExecutors(int numExecutors) {
        this.numExecutors = ONE_SHOT_EXECUTORS;
    }

    @DataBoundSetter
    public void setVmReadyTimeoutSeconds(int vmReadyTimeoutSeconds) {
        this.vmReadyTimeoutSeconds = vmReadyTimeoutSeconds;
    }

    @DataBoundSetter
    public void setVmReadyPollIntervalSeconds(int vmReadyPollIntervalSeconds) {
        this.vmReadyPollIntervalSeconds = vmReadyPollIntervalSeconds;
    }

    @DataBoundSetter
    public void setProvisioningConfig(ProvisioningConfig provisioningConfig) {
        this.provisioningConfig = provisioningConfig != null
                ? provisioningConfig : new CloneProvisioningConfig("");
    }

    /**
     * Returns {@code true} when this template's label set satisfies the given
     * Jenkins {@link Label} expression.
     */
    public boolean matches(Label label) {
        if (label == null) {
            return true;
        }
        return label.matches(Label.parse(templateLabel));
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AgentTemplate> {

        @Override
        public String getDisplayName() {
            return "VM Template";
        }

        @Override
        public AgentTemplate newInstance(StaplerRequest2 req, JSONObject formData) throws FormException {
            AgentTemplate template = (AgentTemplate) super.newInstance(req, formData);
            if (Util.fixEmptyAndTrim(template.getSshCredentialsId()) == null) {
                throw new FormException("SSH credentials are required", "sshCredentialsId");
            }
            return template;
        }

        @POST
        public ListBoxModel doFillSshCredentialsIdItems(@QueryParameter String sshCredentialsId) {
            Jenkins jenkins = Jenkins.get();
            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(sshCredentialsId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            jenkins,
                            StandardUsernameCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(sshCredentialsId);
        }

        @POST
        public FormValidation doCheckSshCredentialsId(@QueryParameter String sshCredentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(sshCredentialsId) == null) {
                return FormValidation.error("SSH credentials are required");
            }
            return FormValidation.ok();
        }
    }
}
