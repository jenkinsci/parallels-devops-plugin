package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.dto.CloneRequest;
import com.parallels.jenkins.api.dto.CloneResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Label;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * Provisioning config for <em>clone</em> mode: a new VM is cloned from an
 * existing base VM registered in the prl-devops-service host.
 */
public final class CloneProvisioningConfig extends ProvisioningConfig {

    private static final Logger LOGGER = Logger.getLogger(CloneProvisioningConfig.class.getName());

    private static final long serialVersionUID = 1L;

    private final String baseVmName;

    @DataBoundConstructor
    public CloneProvisioningConfig(String baseVmName) {
        this.baseVmName = baseVmName;
    }

    public String getBaseVmName() { return baseVmName; }

    @Override
    public VmProvisioningMode getMode() { return VmProvisioningMode.CLONE; }

    @Override
    public boolean canProvision() {
        return Util.fixEmptyAndTrim(baseVmName) != null;
    }

    @Override
    public PrlDevopsPlannedNode provision(String cloudName,
                                          AgentTemplate template,
                                          Label label,
                                          PrlDevopsApiClient apiClient,
                                          Duration timeout,
                                          Duration pollInterval,
                                          ExecutorService executor) throws PrlApiException {
        String sourceVmName = Util.fixEmptyAndTrim(baseVmName);
        if (sourceVmName == null) {
            throw new PrlApiException(
                    "Template '" + template.getTemplateLabel() + "': Base VM Name is not configured.");
        }

        CloneRequest cloneRequest = new CloneRequest(
                "jenkins-" + label + "-" + System.currentTimeMillis(), null);
        LOGGER.fine("[PrlDevops] Requesting clone of '" + sourceVmName + "' for label '" + label + "'");
        CloneResponse cloneResponse = apiClient.cloneVm(sourceVmName, cloneRequest);
        String vmId = cloneResponse.getId();
        LOGGER.fine("[PrlDevops] Clone requested; VM ID=" + vmId);
        return new PrlDevopsPlannedNode(
                cloudName,
                template,
                vmId,
                apiClient,
                timeout,
                pollInterval,
                false,
                executor);
    }

    @Extension
    @Symbol("clone")
    public static class DescriptorImpl extends Descriptor<ProvisioningConfig> {
        @Override
        public String getDisplayName() { return "Clone existing VM (Host mode)"; }
    }
}
