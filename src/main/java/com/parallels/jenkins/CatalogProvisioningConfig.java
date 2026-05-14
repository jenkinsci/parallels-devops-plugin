package com.parallels.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.dto.CatalogManifest;
import com.parallels.jenkins.api.dto.CreateVmRequest;
import com.parallels.jenkins.api.dto.CreateVmResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * Provisioning config for <em>catalog</em> mode: a new VM is created from a
 * Parallels DevOps catalog entry managed by an orchestrator.
 */
public final class CatalogProvisioningConfig extends ProvisioningConfig {

    private static final Logger LOGGER = Logger.getLogger(CatalogProvisioningConfig.class.getName());

    private static final long serialVersionUID = 1L;

    private final String catalogId;
    private String architecture = "arm64";
    private String catalogVersion = "latest";
    private String catalogUrl;
    private String catalogCredentialsId;

    @DataBoundConstructor
    public CatalogProvisioningConfig(String catalogId) {
        this.catalogId = catalogId;
    }

    public String getCatalogId() { return catalogId; }
    public String getArchitecture() { return architecture; }
    public String getCatalogVersion() { return catalogVersion; }
    public String getCatalogUrl() { return catalogUrl; }
    public String getCatalogCredentialsId() { return catalogCredentialsId; }

    @DataBoundSetter
    public void setArchitecture(String architecture) { this.architecture = architecture; }

    @DataBoundSetter
    public void setCatalogVersion(String catalogVersion) { this.catalogVersion = catalogVersion; }

    @DataBoundSetter
    public void setCatalogUrl(String catalogUrl) { this.catalogUrl = catalogUrl; }

    @DataBoundSetter
    public void setCatalogCredentialsId(String catalogCredentialsId) {
        this.catalogCredentialsId = catalogCredentialsId;
    }

    @Override
    public VmProvisioningMode getMode() { return VmProvisioningMode.CATALOG; }

    @Override
    public boolean canProvision() {
        return Util.fixEmptyAndTrim(catalogId) != null
                && Util.fixEmptyAndTrim(catalogUrl) != null;
    }

    @Override
    public PrlDevopsPlannedNode provision(String cloudName,
                                          AgentTemplate template,
                                          Label label,
                                          PrlDevopsApiClient apiClient,
                                          Duration timeout,
                                          Duration pollInterval,
                                          ExecutorService executor) throws PrlApiException {
        String connection = buildCatalogConnectionString();
        CatalogManifest manifest = new CatalogManifest(catalogId, catalogVersion, connection);
        String vmName = "jenkins-" + label + "-" + System.currentTimeMillis();
        CreateVmRequest request = new CreateVmRequest(vmName, architecture, manifest);
        LOGGER.fine("[PrlDevops] Creating VM from catalog '" + catalogId + "' for label '" + label + "'");
        CreateVmResponse response = apiClient.createVmFromCatalog(request);
        String vmId = response.getId();
        LOGGER.fine("[PrlDevops] Catalog VM created; VM ID=" + vmId);
        return new PrlDevopsPlannedNode(
                cloudName,
                template,
                vmId,
                apiClient,
                timeout,
                pollInterval,
                true,
                executor);
    }

    private String buildCatalogConnectionString() throws PrlApiException {
        String credId = Util.fixEmptyAndTrim(catalogCredentialsId);
        String effectiveCatalogUrl = Util.fixEmptyAndTrim(catalogUrl);
        if (credId == null) {
            throw new PrlApiException("Catalog credentials ID is not configured on the template");
        }
        if (effectiveCatalogUrl == null) {
            throw new PrlApiException("Catalog URL is not configured on the template");
        }

        StandardUsernamePasswordCredentials cred = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        Collections.emptyList()),
                CredentialsMatchers.withId(credId));
        if (cred == null) {
            throw new PrlApiException("Catalog credentials '" + credId + "' not found");
        }

        String url = effectiveCatalogUrl.endsWith("/")
                ? effectiveCatalogUrl.substring(0, effectiveCatalogUrl.length() - 1)
                : effectiveCatalogUrl;
        return "host=" + cred.getUsername() + ":" + cred.getPassword().getPlainText() + "@" + url;
    }

    @Extension
    @Symbol("catalog")
    public static class DescriptorImpl extends Descriptor<ProvisioningConfig> {

        @Override
        public String getDisplayName() { return "Create from catalog (Orchestrator mode)"; }

        @Override
        public ProvisioningConfig newInstance(StaplerRequest2 req, JSONObject formData) throws FormException {
            CatalogProvisioningConfig config = (CatalogProvisioningConfig) super.newInstance(req, formData);
            if (Util.fixEmptyAndTrim(config.getCatalogCredentialsId()) == null) {
                throw new FormException("Catalog credentials are required", "catalogCredentialsId");
            }
            if (Util.fixEmptyAndTrim(config.getCatalogId()) == null) {
                throw new FormException("Catalog ID is required", "catalogId");
            }
            return config;
        }

        public ListBoxModel doFillArchitectureItems(@QueryParameter String architecture) {
            ListBoxModel items = new ListBoxModel();
            items.add("arm64", "arm64");
            items.add("x86_64", "x86_64");
            return items;
        }

        @POST
        public ListBoxModel doFillCatalogCredentialsIdItems(
                @QueryParameter String catalogCredentialsId) {
            Jenkins jenkins = Jenkins.get();
            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(catalogCredentialsId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            jenkins,
                            StandardUsernamePasswordCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always()
                    )
                    .includeCurrentValue(catalogCredentialsId);
        }

        @POST
        public FormValidation doCheckCatalogCredentialsId(
                @QueryParameter String catalogCredentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(catalogCredentialsId) == null) {
                return FormValidation.error("Catalog credentials are required");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckCatalogId(@QueryParameter String catalogId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (catalogId == null || catalogId.isBlank()) {
                return FormValidation.error("Catalog ID is required");
            }
            return FormValidation.ok();
        }
    }
}
