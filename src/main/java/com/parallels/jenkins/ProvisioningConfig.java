package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Label;

import java.io.Serializable;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

/**
 * Abstract base for the two provisioning strategies supported by the plugin.
 *
 * <ul>
 *   <li>{@link CloneProvisioningConfig} — clone an existing VM by name (host mode).</li>
 *   <li>{@link CatalogProvisioningConfig} — create a VM from a Parallels DevOps catalog
 *       entry (orchestrator mode).</li>
 * </ul>
 *
 * <p>Implementations are rendered in the UI via
 * {@code <f:dropdownDescriptorSelector>} in the {@code AgentTemplate} config
 * jelly, which automatically shows each implementation's own jelly form without
 * any inline JavaScript.
 */
public abstract class ProvisioningConfig
        extends AbstractDescribableImpl<ProvisioningConfig>
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Returns the {@link VmProvisioningMode} constant this config represents. */
    public abstract VmProvisioningMode getMode();

    /** Returns {@code true} when the fields required for this mode are configured. */
    public abstract boolean canProvision();

    /** Provisions one VM for the given label and returns the planned Jenkins node. */
    public abstract PrlDevopsPlannedNode provision(String cloudName,
                                                   AgentTemplate template,
                                                   Label label,
                                                   PrlDevopsApiClient apiClient,
                                                   Duration timeout,
                                                   Duration pollInterval,
                                                   ExecutorService executor)
            throws PrlApiException;
}
