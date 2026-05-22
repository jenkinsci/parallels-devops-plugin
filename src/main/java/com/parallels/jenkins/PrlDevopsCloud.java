package com.parallels.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parallels.jenkins.api.dto.AuthTokenRequest;
import com.parallels.jenkins.api.ConnectionMode;
import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.PrlDevopsHttpClient;
import com.parallels.jenkins.api.dto.VmStatusResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.Util;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PrlDevopsCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(PrlDevopsCloud.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String serviceUrl;
    private String credentialsId;
    private com.parallels.jenkins.api.ConnectionMode connectionMode;
    private int maxAgents;
    private List<AgentTemplate> templates = new ArrayList<>();

    @DataBoundConstructor
    public PrlDevopsCloud(String name) {
        super(name);
    }

    public String getServiceUrl() { return serviceUrl; }
    public String getCredentialsId() { return credentialsId; }
    public com.parallels.jenkins.api.ConnectionMode getConnectionMode() { return connectionMode; }
    public int getMaxAgents() { return maxAgents; }
    public List<AgentTemplate> getTemplates() { return Collections.unmodifiableList(templates); }

    @DataBoundSetter
    public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }
    @DataBoundSetter
    public void setCredentialsId(String credentialsId) { this.credentialsId = credentialsId; }
    @DataBoundSetter
    public void setConnectionMode(com.parallels.jenkins.api.ConnectionMode connectionMode) { this.connectionMode = connectionMode; }
    @DataBoundSetter
    public void setMaxAgents(int maxAgents) { this.maxAgents = maxAgents; }
    @DataBoundSetter
    public void setTemplates(List<AgentTemplate> templates) {
        this.templates = templates != null ? new ArrayList<>(templates) : new ArrayList<>();
    }

    /**
     * Returns the first {@link AgentTemplate} whose label set satisfies the
     * given Jenkins {@link hudson.model.Label}, or {@code null} if none match.
     */
    public AgentTemplate getTemplateForLabel(hudson.model.Label label) {
        for (AgentTemplate t : templates) {
            if (t.matches(label)) {
                return t;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Cloud provisioning
    // -------------------------------------------------------------------------

    /**
     * Counts agents belonging to this cloud that are "active" — either online or
     * within the VM-ready grace window (regardless of SSH state).
     *
     * <p>Counting offline-but-young nodes is critical: when the SSH launcher fails
     * (e.g. host key rejection, SSH not yet ready), the computer is neither
     * {@code isOnline()} nor {@code isConnecting()}.  Without counting those nodes
     * we would report {@code active=0} even when two VMs are running, causing the
     * provisioner to keep requesting more VMs.
     */
    private long countActiveAgents() {
        return Jenkins.get().getNodes().stream()
            .filter(n -> n instanceof PrlDevopsAgent agent
                && name.equals(agent.getCloudName()))
                .filter(n -> {
                    Computer c = n.toComputer();
                    if (c == null) return false;
                    if (c.isOnline()) return true;
                    // Count any node (connecting, SSH-failed, or temporarily offline)
                    // that is still within the VM-ready grace window.  The reconciler
                    // will remove it if the VM is genuinely gone.
                        PrlDevopsAgent prl = (PrlDevopsAgent) n;
                    long ageSeconds =
                            (System.currentTimeMillis() - prl.getProvisionedAt()) / 1000L;
                    long graceSeconds = prl.getTemplate().getVmReadyTimeoutSeconds() * 2L;
                    return ageSeconds < graceSeconds;
                })
                .count();
    }

    @Override
    public boolean canProvision(CloudState state) {
        AgentTemplate template = getTemplateForLabel(state.getLabel());
        if (template == null) {
            LOGGER.warning("[PrlDevops] canProvision: no template matches label '"
                    + state.getLabel() + "'. Check the template label in cloud configuration.");
            return false;
        }
        if (Util.fixEmptyAndTrim(serviceUrl) == null) {
            LOGGER.warning("[PrlDevops] canProvision: Service URL is not configured on cloud '" + name + "'.");
            return false;
        }
        if (Util.fixEmptyAndTrim(credentialsId) == null) {
            LOGGER.warning("[PrlDevops] canProvision: credentials are not configured on cloud '" + name + "'.");
            return false;
        }
        if (Util.fixEmptyAndTrim(template.getSshCredentialsId()) == null) {
            LOGGER.warning("[PrlDevops] canProvision: template '" + template.getTemplateLabel()
                    + "' has no SSH credentials configured.");
            return false;
        }
        if (!template.canProvision()) {
            LOGGER.warning("[PrlDevops] canProvision: template '" + template.getTemplateLabel()
                    + "' has no provisioning config (catalog ID / base VM name missing).");
            return false;
        }
        // Prevent Jenkins from even scheduling a provisioning cycle when already at cap.
        return maxAgents <= 0 || countActiveAgents() < maxAgents;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        Label label = state.getLabel();
        AgentTemplate template = getTemplateForLabel(label);
        if (template == null) {
            LOGGER.warning("[PrlDevops] No matching template for label: " + label);
            return Collections.emptyList();
        }
        if (Util.fixEmptyAndTrim(template.getSshCredentialsId()) == null) {
            LOGGER.warning("[PrlDevops] Template '" + template.getTemplateLabel()
                    + "' has no SSH credentials configured. Skipping provisioning.");
            return Collections.emptyList();
        }
        if (!template.canProvision()) {
            LOGGER.warning("[PrlDevops] Template '" + template.getTemplateLabel()
                + "' is missing provisioning configuration. Skipping provisioning.");
            return Collections.emptyList();
        }

        long activeAgents = countActiveAgents();
        int budget = maxAgents > 0 ? (int) Math.max(0, maxAgents - activeAgents) : excessWorkload;
        int toProvision = Math.min(excessWorkload, budget);

        if (toProvision <= 0) {
            LOGGER.fine("[PrlDevops] maxAgents cap reached (active=" + activeAgents
                    + ", max=" + maxAgents + "). Skipping provisioning.");
            return Collections.emptyList();
        }

        PrlDevopsApiClient apiClient;
        try {
            apiClient = buildApiClient();
        } catch (PrlApiException e) {
            LOGGER.log(Level.WARNING, "[PrlDevops] Cannot build API client: " + e.getMessage(), e);
            return Collections.emptyList();
        }

        Duration timeout = Duration.ofSeconds(template.getVmReadyTimeoutSeconds());
        Duration pollInterval = Duration.ofSeconds(template.getVmReadyPollIntervalSeconds());

        List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();
        for (int i = 0; i < toProvision; i++) {
            try {
                plannedNodes.add(template.provision(
                        name,
                        label,
                        apiClient,
                        timeout,
                        pollInterval,
                        Computer.threadPoolForRemoting));
            } catch (PrlApiException e) {
                LOGGER.log(Level.WARNING,
                        "[PrlDevops] Failed to provision VM for template '"
                                + template.getTemplateLabel() + "': " + e.getMessage(), e);
            }
        }
        return plannedNodes;
    }

    // -------------------------------------------------------------------------
    // API client factory — protected so subclasses / tests can override
    // -------------------------------------------------------------------------

    /**
     * Looks up the configured credentials and constructs a {@link PrlDevopsApiClient}
     * pointed at the configured service URL.
     *
     * <ul>
     *   <li>{@link StringCredentials} — API key mode: sends {@code X-API-Key: <encoded>}.</li>
     *   <li>{@link StandardUsernamePasswordCredentials} — Bearer mode: exchanges
     *       username+password for a JWT via {@code POST /api/v1/auth/token}.</li>
     * </ul>
     *
     * <p>Protected so tests can override and inject a mock client.
     *
     * @throws PrlApiException if credentials cannot be resolved.
     */
    protected PrlDevopsApiClient buildApiClient() throws PrlApiException {
        if (Util.fixEmptyAndTrim(credentialsId) == null) {
            throw new PrlApiException("No credentials configured on cloud '" + name + "'");
        }

        PrlDevopsHttpClient.Builder builder = new PrlDevopsHttpClient.Builder()
                .baseUrl(serviceUrl)
                .mode(connectionMode != null ? connectionMode : ConnectionMode.HOST);

        // API key (Secret Text) — use X-API-Key header directly
        StringCredentials sc = CredentialsHelper.findStringCredential(credentialsId, Jenkins.get());
        if (sc != null) {
            return builder.apiKey(sc.getSecret().getPlainText()).build();
        }

        // Username + Password — exchange for a JWT Bearer token
        StandardUsernamePasswordCredentials upc =
                CredentialsHelper.findUsernamePasswordCredential(credentialsId, Jenkins.get());
        if (upc != null) {
            return builder.bearerToken(fetchTokenWithPassword(upc)).build();
        }

        throw new PrlApiException(
                "Credentials '" + credentialsId + "' not found. Configure a 'Secret text' "
                        + "(API key) or 'Username with password' credential.");
    }

    /**
     * Exchanges a username+password pair for a bearer token by calling
     * {@code POST {serviceUrl}/api/v1/auth/token}.
     */
    private String fetchTokenWithPassword(StandardUsernamePasswordCredentials upc) throws PrlApiException {
        String base = Util.fixEmptyAndTrim(serviceUrl);
        if (base == null) {
            throw new PrlApiException("Service URL is not configured on cloud '" + name + "'");
        }
        if (!base.endsWith("/")) {
            base += "/";
        }
        String authUrl = base + "api/v1/auth/token";
        String jsonBody = serializeAuthTokenRequest(upc.getUsername(), upc.getPassword().getPlainText());

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(authUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"")
                                .matcher(res.body());
                if (m.find()) {
                    return m.group(1);
                }
                throw new PrlApiException(
                        "Auth response did not contain a 'token' field. Body: " + res.body());
            }
            throw new PrlApiException(
                    "Auth token request failed. HTTP " + res.statusCode() + ": " + res.body());
        } catch (IOException e) {
            throw new PrlApiException("Network error during auth token fetch: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrlApiException("Auth token fetch interrupted", e);
        }
    }

    private static String serializeAuthTokenRequest(String email, String password) {
        try {
            return OBJECT_MAPPER.writeValueAsString(new AuthTokenRequest(email, password));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialise auth token request", e);
        }
    }

    // -------------------------------------------------------------------------
    // Node reconciler — removes Jenkins nodes whose backing VMs no longer exist
    // -------------------------------------------------------------------------

    /**
     * Background worker that runs every 60 seconds and removes any
     * {@link PrlDevopsAgent} node whose VM has been deleted outside the plugin
     * (e.g. manually via prl-devops-service UI or API).
     *
     * <p>Only offline nodes are inspected — online nodes are by definition healthy.
     * A node is removed when:
     * <ul>
     *   <li>Its owning {@link PrlDevopsCloud} no longer exists in Jenkins, or</li>
     *   <li>Calling {@code getVmStatus()} returns an error state or throws a
     *       {@link PrlApiException} (typically HTTP 404 — VM not found).</li>
     * </ul>
     */
    @Extension
    public static class NodeReconciler extends AsyncPeriodicWork {

        private static final Logger LOG = Logger.getLogger(NodeReconciler.class.getName());

        public NodeReconciler() {
            super("PrlDevops Node Reconciler");
        }

        @Override
        public long getRecurrencePeriod() {
            return MIN; // every 60 seconds
        }

        @Override
        protected void execute(TaskListener listener) {
            Jenkins jenkins = Jenkins.get();
            for (Node node : new ArrayList<>(jenkins.getNodes())) {
                if (!(node instanceof PrlDevopsAgent agent)) {
                    continue;
                }
                Computer computer = agent.toComputer();

                // Computer not yet initialised — node was just added; skip this cycle.
                if (computer == null) {
                    continue;
                }

                // Online nodes are healthy — nothing to do.
                if (computer.isOnline()) {
                    continue;
                }

                Cloud cloud = jenkins.clouds.getByName(agent.getCloudName());
                if (!(cloud instanceof PrlDevopsCloud prlCloud)) {
                    LOG.fine("[PrlDevops] Removing node " + agent.getNodeName()
                            + " — owning cloud '" + agent.getCloudName() + "' no longer exists.");
                    removeNode(jenkins, agent);
                    continue;
                }

                try {
                    PrlDevopsApiClient client = prlCloud.buildApiClient();
                    VmStatusResponse status = client.getVmStatus(agent.getVmId());
                    String state = status.getStatus() != null
                            ? status.getStatus().toLowerCase(Locale.ROOT) : "";

                    switch (state) {
                        case "running":
                            if (computer.isConnecting()) {
                                // Launcher is actively retrying.
                                // Allow up to 2× the VM-ready timeout; after that the node
                                // is either a stale leftover from a previous session or the
                                // VM is genuinely unreachable — clean it up.
                                long ageSeconds =
                                        (System.currentTimeMillis() - agent.getProvisionedAt()) / 1000L;
                                long graceSeconds =
                                        agent.getTemplate().getVmReadyTimeoutSeconds() * 2L;
                                if (ageSeconds >= graceSeconds) {
                                    LOG.fine("[PrlDevops] Removing node " + agent.getNodeName()
                                            + " — VM " + agent.getVmId()
                                            + " is running but has been connecting for "
                                            + ageSeconds + "s (grace=" + graceSeconds
                                            + "s). Deleting stale VM.");
                                    deleteVmQuietly(client, agent.getVmId());
                                    removeNode(jenkins, agent);
                                }
                                // else: within grace period — leave it alone
                            } else {
                                // Not actively connecting. Check if the node was recently
                                // launched — the JNLP agent may still be starting up and
                                // hasn't established its WebSocket connection yet.
                                long ageSeconds =
                                        (System.currentTimeMillis() - agent.getProvisionedAt()) / 1000L;
                                long graceSeconds =
                                        agent.getTemplate().getVmReadyTimeoutSeconds() * 2L;
                                if (ageSeconds < graceSeconds) {
                                    // Within grace period — leave it alone.
                                    break;
                                }
                                // Beyond grace period AND offline AND not connecting →
                                // the agent failed to come online; delete the VM.
                                LOG.fine("[PrlDevops] Removing node " + agent.getNodeName()
                                        + " — VM " + agent.getVmId()
                                        + " is running but node has been offline for "
                                        + ageSeconds + "s (grace=" + graceSeconds
                                        + "s). Deleting VM.");
                                deleteVmQuietly(client, agent.getVmId());
                                removeNode(jenkins, agent);
                            }
                            break;
                        case "error":
                            LOG.fine("[PrlDevops] Removing node " + agent.getNodeName()
                                    + " — VM " + agent.getVmId() + " is in error state.");
                            deleteVmQuietly(client, agent.getVmId());
                            removeNode(jenkins, agent);
                            break;
                        default:
                            // stopped / starting / pending — VM is transitioning; leave it.
                            break;
                    }
                } catch (PrlApiException e) {
                    // HTTP 404 or network error — VM is gone, remove the stale node.
                    LOG.fine("[PrlDevops] Removing node " + agent.getNodeName()
                            + " — VM " + agent.getVmId() + " no longer exists: " + e.getMessage());
                    removeNode(jenkins, agent);
                }
            }
        }

        private static void deleteVmQuietly(PrlDevopsApiClient client, String vmId) {
            try {
                client.deleteVm(vmId);
                LOG.fine("[PrlDevops] Deleted VM " + vmId);
            } catch (PrlApiException e) {
                LOG.log(Level.WARNING, "[PrlDevops] Could not delete VM " + vmId
                        + " during reconciliation: " + e.getMessage(), e);
            }
        }

        private static void removeNode(Jenkins jenkins, PrlDevopsAgent agent) {
            try {
                jenkins.removeNode(agent);
            } catch (IOException e) {
                LOG.log(Level.WARNING,
                        "[PrlDevops] Failed to remove stale node " + agent.getNodeName() + ": " + e.getMessage(), e);
            }
        }
    }

    @Extension
    @org.jenkinsci.Symbol("parallelsDevops")
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Parallels Devops Cloud";
        }

        @Override
        public Cloud newInstance(StaplerRequest2 req, JSONObject formData) throws FormException {
            PrlDevopsCloud cloud = (PrlDevopsCloud) super.newInstance(req, formData);
            if (Util.fixEmptyAndTrim(cloud.getCredentialsId()) == null) {
                throw new FormException("API credentials are required", "credentialsId");
            }
            if (cloud.getConnectionMode() == com.parallels.jenkins.api.ConnectionMode.ORCHESTRATOR) {
                for (AgentTemplate t : cloud.getTemplates()) {
                    if (t.getProvisioningConfig() instanceof CloneProvisioningConfig) {
                        throw new FormException(
                                "Template '" + t.getTemplateLabel() + "': Clone provisioning is not supported "
                                        + "in Orchestrator mode. Use 'Create from catalog' instead.",
                                "templates");
                    }
                }
            }
            return cloud;
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            Jenkins jenkins = Jenkins.get();
            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(credentialsId);
            }

            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            jenkins,
                            StringCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always()
                    )
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            jenkins,
                            StandardUsernamePasswordCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always()
                    )
                    .includeCurrentValue(credentialsId);
        }

        @POST
        public FormValidation doCheckCredentialsId(@QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(credentialsId) == null) {
                return FormValidation.error("API credentials are required");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter("serviceUrl") String serviceUrl,
                @QueryParameter("credentialsId") String credentialsId) {

            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (Util.fixEmptyAndTrim(serviceUrl) == null) {
                return FormValidation.error("Service URL is required");
            }
            if (Util.fixEmptyAndTrim(credentialsId) == null) {
                return FormValidation.error("API credentials are required");
            }

            String base = serviceUrl.endsWith("/") ? serviceUrl : serviceUrl + "/";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

            // --- API Key (Secret Text) — test with X-API-Key on a protected endpoint ---
            StringCredentials sc = CredentialsHelper.findStringCredential(credentialsId, Jenkins.get());
            if (sc != null) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(base + "api/v1/auth/api_keys"))
                            .header("X-API-Key", sc.getSecret().getPlainText())
                            .header("Accept", "application/json")
                            .GET()
                            .build();
                    HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 200) {
                        return FormValidation.ok("Connected");
                    } else if (res.statusCode() == 401) {
                        return FormValidation.error("Authentication failed (401). Check the API key 'encoded' value.");
                    } else {
                        return FormValidation.error("Unexpected response. HTTP " + res.statusCode());
                    }
                } catch (IOException | IllegalArgumentException e) {
                    return FormValidation.error("Connection error: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return FormValidation.error("Connection interrupted");
                }
            }

            // --- Username + Password — exchange for JWT then validate it ---
            StandardUsernamePasswordCredentials upc =
                    CredentialsHelper.findUsernamePasswordCredential(credentialsId, Jenkins.get());
            if (upc != null) {
                try {
                    // Step 1: get token
                    String jsonBody = serializeAuthTokenRequest(
                            upc.getUsername(), upc.getPassword().getPlainText());
                    HttpRequest authReq = HttpRequest.newBuilder()
                            .uri(URI.create(base + "api/v1/auth/token"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                            .build();
                    HttpResponse<String> authRes = client.send(authReq, HttpResponse.BodyHandlers.ofString());
                    if (authRes.statusCode() != 200) {
                        return FormValidation.error(
                                "Login failed. HTTP " + authRes.statusCode() + ": " + authRes.body());
                    }
                    java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"")
                                    .matcher(authRes.body());
                    if (!m.find()) {
                        return FormValidation.error("Login succeeded but no 'token' field in response.");
                    }
                    String token = m.group(1);

                    // Step 2: validate token
                    String validateBody = "{\"token\":\"" + token.replace("\"", "\\\"") + "\"}";
                    HttpRequest valReq = HttpRequest.newBuilder()
                            .uri(URI.create(base + "api/v1/auth/token/validate"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(validateBody))
                            .build();
                    HttpResponse<String> valRes = client.send(valReq, HttpResponse.BodyHandlers.ofString());
                    if (valRes.statusCode() == 200 && valRes.body() != null
                            && valRes.body().contains("\"valid\":true")) {
                        return FormValidation.ok("Connected");
                    } else {
                        return FormValidation.error(
                                "Token validation failed. HTTP " + valRes.statusCode() + ": " + valRes.body());
                    }
                } catch (IOException | IllegalArgumentException e) {
                    return FormValidation.error("Connection error: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return FormValidation.error("Connection interrupted");
                }
            }

            return FormValidation.error(
                    "Credentials '" + credentialsId + "' not found. Configure a 'Secret text' "
                            + "(API key) or 'Username with password' credential.");
        }

    }
}
