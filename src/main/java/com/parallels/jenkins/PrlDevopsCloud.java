package com.parallels.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
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
        if (template == null) return false;
        if (Util.fixEmptyAndTrim(serviceUrl) == null) return false;
        if (Util.fixEmptyAndTrim(credentialsId) == null) return false;
        if (Util.fixEmptyAndTrim(template.getSshCredentialsId()) == null) return false;
        if (!template.canProvision()) return false;
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
     * Resolves the bearer token from the configured credentials and constructs
     * a {@link PrlDevopsApiClient} pointed at the configured service URL.
     *
     * <p>Protected so tests can override and inject a mock client.
     *
     * @throws PrlApiException if credentials cannot be resolved.
     */
    protected PrlDevopsApiClient buildApiClient() throws PrlApiException {
        String token = resolveToken();
        return new PrlDevopsHttpClient.Builder()
                .baseUrl(serviceUrl)
                .bearerToken(token)
                .mode(connectionMode != null ? connectionMode : ConnectionMode.HOST)
                .build();
    }

    /**
     * Looks up the configured credentials and returns a bearer token string.
     *
     * <p>Supports two credential types:
     * <ul>
     *   <li>{@link StringCredentials} — the secret text is used directly as a bearer token.</li>
     *   <li>{@link StandardUsernamePasswordCredentials} — the plugin POSTs to
     *       {@code POST /api/v1/auth/token} to exchange username+password for a token.</li>
     * </ul>
     *
     * @throws PrlApiException if credentials are not configured, cannot be found, or the
     *                         auth exchange fails.
     */
    private String resolveToken() throws PrlApiException {
        if (Util.fixEmptyAndTrim(credentialsId) == null) {
            throw new PrlApiException("No credentials configured on cloud '" + name + "'");
        }

        // 1. Try Secret Text (direct bearer token)
        StringCredentials sc = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StringCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
        if (sc != null) {
            return sc.getSecret().getPlainText();
        }

        // 2. Try Username + Password — exchange for a token via the auth endpoint
        StandardUsernamePasswordCredentials upc = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
        if (upc != null) {
            return fetchTokenWithPassword(upc);
        }

        throw new PrlApiException(
                "Credentials '" + credentialsId + "' not found. Configure a 'Secret text' "
                        + "(bearer token) or 'Username with password' credential.");
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

        String username = upc.getUsername().replace("\"", "\\\"");
        String password = upc.getPassword().getPlainText().replace("\"", "\\\"");
        String jsonBody = "{\"email\":\"" + username + "\",\"password\":\"" + password + "\"}";

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
                    LOG.info("[PrlDevops] Removing node " + agent.getNodeName()
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
                                    LOG.info("[PrlDevops] Removing node " + agent.getNodeName()
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
                                LOG.info("[PrlDevops] Removing node " + agent.getNodeName()
                                        + " — VM " + agent.getVmId()
                                        + " is running but node has been offline for "
                                        + ageSeconds + "s (grace=" + graceSeconds
                                        + "s). Deleting VM.");
                                deleteVmQuietly(client, agent.getVmId());
                                removeNode(jenkins, agent);
                            }
                            break;
                        case "error":
                            LOG.info("[PrlDevops] Removing node " + agent.getNodeName()
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
                    LOG.info("[PrlDevops] Removing node " + agent.getNodeName()
                            + " — VM " + agent.getVmId() + " no longer exists: " + e.getMessage());
                    removeNode(jenkins, agent);
                }
            }
        }

        private static void deleteVmQuietly(PrlDevopsApiClient client, String vmId) {
            try {
                client.deleteVm(vmId);
                LOG.info("[PrlDevops] Deleted VM " + vmId);
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

            // Extract token
            String token = "";
            if (Util.fixEmptyAndTrim(credentialsId) != null) {
                StringCredentials sc = CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentials(
                                StringCredentials.class,
                                Jenkins.get(),
                                ACL.SYSTEM,
                                Collections.emptyList()
                        ),
                        CredentialsMatchers.withId(credentialsId)
                );
                if (sc != null) {
                    // Secret text token directly provided
                    token = sc.getSecret().getPlainText();
                } else {
                    StandardUsernamePasswordCredentials upc = CredentialsMatchers.firstOrNull(
                            CredentialsProvider.lookupCredentials(
                                    StandardUsernamePasswordCredentials.class,
                                    Jenkins.get(),
                                    ACL.SYSTEM,
                                    Collections.emptyList()
                            ),
                            CredentialsMatchers.withId(credentialsId)
                    );
                    if (upc != null) {
                        try {
                            String baseUrl = serviceUrl;
                            if (!baseUrl.endsWith("/")) { baseUrl += "/"; }
                            String authUrl = baseUrl + "api/v1/auth/token";
                            
                            String username = upc.getUsername().replace("\"", "\\\"");
                            String password = upc.getPassword().getPlainText().replace("\"", "\\\"");
                            String jsonBody = "{\"email\":\"" + username + "\",\"password\":\"" + password + "\"}";

                            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                            HttpRequest authReq = HttpRequest.newBuilder()
                                    .uri(URI.create(authUrl))
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                    .build();

                            HttpResponse<String> authRes = client.send(authReq, HttpResponse.BodyHandlers.ofString());
                            if (authRes.statusCode() == 200) {
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(authRes.body());
                                if (m.find()) {
                                    token = m.group(1);
                                } else {
                                    return FormValidation.error("Auth successful but no 'token' property found in JSON.");
                                }
                            } else {
                                return FormValidation.error("Auth token POST failed. HTTP " + authRes.statusCode() + " " + authRes.body());
                            }
                        } catch (IOException | IllegalArgumentException e) {
                            return FormValidation.error("Authentication error: " + e.getMessage());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return FormValidation.error("Authentication interrupted");
                        }
                    }
                }
            }

            if (Util.fixEmptyAndTrim(token) == null) {
                return FormValidation.error("Credentials not found or empty");
            }

            try {
                String urlText = serviceUrl;
                if (!urlText.endsWith("/")) {
                    urlText += "/";
                }
                urlText += "api/v1/health/system?full=true";

                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlText))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return FormValidation.ok("Connected");
                } else {
                    return FormValidation.error("Failed to connect. HTTP Status: " + response.statusCode());
                }
            } catch (IOException | IllegalArgumentException e) {
                return FormValidation.error("Connection error: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FormValidation.error("Connection interrupted");
            }
        }
    }
}
