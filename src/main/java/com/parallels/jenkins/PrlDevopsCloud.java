package com.parallels.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.PrlDevopsHttpClient;
import com.parallels.jenkins.api.dto.CatalogManifest;
import com.parallels.jenkins.api.dto.CloneRequest;
import com.parallels.jenkins.api.dto.CloneResponse;
import com.parallels.jenkins.api.dto.CreateVmRequest;
import com.parallels.jenkins.api.dto.CreateVmResponse;
import com.parallels.jenkins.api.dto.VmStatusResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.Util;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
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
    private ConnectionMode connectionMode;
    private int maxAgents;
    private List<AgentTemplate> templates = new ArrayList<>();

    @DataBoundConstructor
    public PrlDevopsCloud(String name) {
        super(name);
    }

    public String getServiceUrl() { return serviceUrl; }
    public String getCredentialsId() { return credentialsId; }
    public ConnectionMode getConnectionMode() { return connectionMode; }
    public int getMaxAgents() { return maxAgents; }
    public List<AgentTemplate> getTemplates() { return Collections.unmodifiableList(templates); }

    @DataBoundSetter
    public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }
    @DataBoundSetter
    public void setCredentialsId(String credentialsId) { this.credentialsId = credentialsId; }
    @DataBoundSetter
    public void setConnectionMode(ConnectionMode connectionMode) { this.connectionMode = connectionMode; }
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

    @Override
    public boolean canProvision(CloudState state) {
        AgentTemplate template = getTemplateForLabel(state.getLabel());
        return template != null
                && Util.fixEmptyAndTrim(serviceUrl) != null
                && Util.fixEmptyAndTrim(credentialsId) != null;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        Label label = state.getLabel();
        AgentTemplate template = getTemplateForLabel(label);
        if (template == null) {
            LOGGER.warning("[PrlDevops] No matching template for label: " + label);
            return Collections.emptyList();
        }

        // Count only nodes that are genuinely active:
        //   - isOnline()      → SSH connected, build may be running
        //   - isConnecting()  → SSH launcher is retrying, BUT only within the VM-ready
        //                       timeout window.  Nodes that are still "connecting" after
        //                       2× the timeout are stale leftovers from a previous Jenkins
        //                       session and must NOT consume budget (the reconciler will
        //                       remove them once the VM-status check detects them as gone).
        long activeAgents = Jenkins.get().getNodes().stream()
                .filter(n -> n instanceof PrlDevopsSlave
                        && name.equals(((PrlDevopsSlave) n).getCloudName()))
                .filter(n -> {
                    Computer c = n.toComputer();
                    if (c == null) return false;
                    if (c.isOnline()) return true;
                    if (c.isConnecting()) {
                        PrlDevopsSlave prl = (PrlDevopsSlave) n;
                        long ageSeconds =
                                (System.currentTimeMillis() - prl.getProvisionedAt()) / 1000L;
                        long graceSeconds = prl.getTemplate().getVmReadyTimeoutSeconds() * 2L;
                        return ageSeconds < graceSeconds;
                    }
                    return false;
                })
                .count();

        int budget = maxAgents > 0 ? (int) Math.max(0, maxAgents - activeAgents) : excessWorkload;
        int toProvision = Math.min(excessWorkload, budget);

        if (toProvision <= 0) {
            LOGGER.info("[PrlDevops] maxAgents cap reached (active=" + activeAgents
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
                String vmId;
                boolean startOnCreate;
                if (template.getProvisioningMode() == VmProvisioningMode.CATALOG) {
                    vmId = provisionFromCatalog(apiClient, template, label);
                    startOnCreate = true; // API starts VM on creation
                } else {
                    vmId = provisionFromClone(apiClient, template, label);
                    startOnCreate = false; // clone comes up stopped; PlannedNode calls startVm()
                }
                plannedNodes.add(new PrlDevopsPlannedNode(
                        name, template, vmId, apiClient, timeout, pollInterval,
                        startOnCreate, Computer.threadPoolForRemoting));
            } catch (PrlApiException e) {
                LOGGER.log(Level.WARNING,
                        "[PrlDevops] Failed to provision VM for template '"
                                + template.getBaseVmName() + "': " + e.getMessage(), e);
            }
        }
        return plannedNodes;
    }

    private String provisionFromClone(PrlDevopsApiClient apiClient,
                                      AgentTemplate template,
                                      Label label) throws PrlApiException {
        CloneRequest cloneRequest = new CloneRequest(
                "jenkins-" + label + "-" + System.currentTimeMillis(), null);
        LOGGER.info("[PrlDevops] Requesting clone of '" + template.getBaseVmName()
                + "' for label '" + label + "'");
        CloneResponse cloneResponse = apiClient.cloneVm(template.getBaseVmName(), cloneRequest);
        String vmId = cloneResponse.getId();
        LOGGER.info("[PrlDevops] Clone requested; VM ID=" + vmId);
        return vmId;
    }

    private String provisionFromCatalog(PrlDevopsApiClient apiClient,
                                        AgentTemplate template,
                                        Label label) throws PrlApiException {
        String connection = buildCatalogConnectionString(template);
        CatalogManifest manifest = new CatalogManifest(
                template.getCatalogId(),
                template.getCatalogVersion(),
                connection);
        String vmName = "jenkins-" + label + "-" + System.currentTimeMillis();
        CreateVmRequest request = new CreateVmRequest(vmName, template.getArchitecture(), manifest);
        LOGGER.info("[PrlDevops] Creating VM from catalog '" + template.getCatalogId()
                + "' for label '" + label + "'");
        CreateVmResponse response = apiClient.createVmFromCatalog(request);
        String vmId = response.getId();
        LOGGER.info("[PrlDevops] Catalog VM created; VM ID=" + vmId);
        return vmId;
    }

    /**
     * Resolves the catalog credentials and builds the connection string in the format:
     * {@code host=username:password@https://catalog.example.com}
     */
    private String buildCatalogConnectionString(AgentTemplate template) throws PrlApiException {
        String credId = template.getCatalogCredentialsId();
        String catalogUrl = template.getCatalogUrl();
        if (credId == null || credId.isBlank()) {
            throw new PrlApiException("Catalog credentials ID is not configured on the template");
        }
        if (catalogUrl == null || catalogUrl.isBlank()) {
            throw new PrlApiException("Catalog URL is not configured on the template");
        }
        java.util.List<StandardUsernamePasswordCredentials> matches =
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        Collections.emptyList());
        StandardUsernamePasswordCredentials cred = CredentialsMatchers.firstOrNull(
                matches, CredentialsMatchers.withId(credId));
        if (cred == null) {
            throw new PrlApiException("Catalog credentials '" + credId + "' not found");
        }
        String url = catalogUrl.endsWith("/") ? catalogUrl.substring(0, catalogUrl.length() - 1) : catalogUrl;
        return "host=" + cred.getUsername() + ":" + cred.getPassword().getPlainText() + "@" + url;
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
                .mode(connectionMode != null
                        ? com.parallels.jenkins.api.ConnectionMode.valueOf(connectionMode.name())
                        : com.parallels.jenkins.api.ConnectionMode.HOST)
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
     * {@link PrlDevopsSlave} node whose VM has been deleted outside the plugin
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
                if (!(node instanceof PrlDevopsSlave)) {
                    continue;
                }
                PrlDevopsSlave slave = (PrlDevopsSlave) node;
                Computer computer = slave.toComputer();

                // Computer not yet initialised — node was just added; skip this cycle.
                if (computer == null) {
                    continue;
                }

                // Online nodes are healthy — nothing to do.
                if (computer.isOnline()) {
                    continue;
                }

                Cloud cloud = jenkins.clouds.getByName(slave.getCloudName());
                if (!(cloud instanceof PrlDevopsCloud)) {
                    LOG.info("[PrlDevops] Removing node " + slave.getNodeName()
                            + " — owning cloud '" + slave.getCloudName() + "' no longer exists.");
                    removeNode(jenkins, slave);
                    continue;
                }

                PrlDevopsCloud prlCloud = (PrlDevopsCloud) cloud;
                try {
                    PrlDevopsApiClient client = prlCloud.buildApiClient();
                    VmStatusResponse status = client.getVmStatus(slave.getVmId());
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
                                        (System.currentTimeMillis() - slave.getProvisionedAt()) / 1000L;
                                long graceSeconds =
                                        slave.getTemplate().getVmReadyTimeoutSeconds() * 2L;
                                if (ageSeconds >= graceSeconds) {
                                    LOG.info("[PrlDevops] Removing node " + slave.getNodeName()
                                            + " — VM " + slave.getVmId()
                                            + " is running but has been connecting for "
                                            + ageSeconds + "s (grace=" + graceSeconds
                                            + "s). Deleting stale VM.");
                                    deleteVmQuietly(client, slave.getVmId());
                                    removeNode(jenkins, slave);
                                }
                                // else: within grace period — leave it alone
                            } else {
                                // Not actively connecting. Check if the node was recently
                                // launched — the JNLP agent may still be starting up and
                                // hasn't established its WebSocket connection yet.
                                long ageSeconds =
                                        (System.currentTimeMillis() - slave.getProvisionedAt()) / 1000L;
                                long graceSeconds =
                                        slave.getTemplate().getVmReadyTimeoutSeconds() * 2L;
                                if (ageSeconds < graceSeconds) {
                                    // Within grace period — leave it alone.
                                    break;
                                }
                                // Beyond grace period AND offline AND not connecting →
                                // the agent failed to come online; delete the VM.
                                LOG.info("[PrlDevops] Removing node " + slave.getNodeName()
                                        + " — VM " + slave.getVmId()
                                        + " is running but node has been offline for "
                                        + ageSeconds + "s (grace=" + graceSeconds
                                        + "s). Deleting VM.");
                                deleteVmQuietly(client, slave.getVmId());
                                removeNode(jenkins, slave);
                            }
                            break;
                        case "error":
                            LOG.info("[PrlDevops] Removing node " + slave.getNodeName()
                                    + " — VM " + slave.getVmId() + " is in error state.");
                            deleteVmQuietly(client, slave.getVmId());
                            removeNode(jenkins, slave);
                            break;
                        default:
                            // stopped / starting / pending — VM is transitioning; leave it.
                            break;
                    }
                } catch (PrlApiException e) {
                    // HTTP 404 or network error — VM is gone, remove the stale node.
                    LOG.info("[PrlDevops] Removing node " + slave.getNodeName()
                            + " — VM " + slave.getVmId() + " no longer exists: " + e.getMessage());
                    removeNode(jenkins, slave);
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

        private static void removeNode(Jenkins jenkins, PrlDevopsSlave slave) {
            try {
                jenkins.removeNode(slave);
            } catch (IOException e) {
                LOG.log(Level.WARNING,
                        "[PrlDevops] Failed to remove stale node " + slave.getNodeName() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Tears down the cloned VM immediately after each build completes.
     *
     * <p>This makes every {@link PrlDevopsSlave} truly one-shot:
     * <ol>
     *   <li>Build finishes (success or failure)
     *   <li>VM is deleted via the Parallels DevOps API
     *   <li>Node is removed from Jenkins
     * </ol>
     * Without this, Jenkins sees the node as reusable and will not provision
     * a new clone for the next queued build.
     */
    @Extension
    public static final class BuildCompletionListener extends RunListener<Run<?, ?>> {

        private static final Logger LOG =
                Logger.getLogger(BuildCompletionListener.class.getName());

        @Override
        public void onFinalized(Run<?, ?> run) {
            if (!(run instanceof AbstractBuild)) {
                return; // Pipeline runs are not handled here
            }
            Node node = ((AbstractBuild<?, ?>) run).getBuiltOn();
            if (!(node instanceof PrlDevopsSlave)) {
                return;
            }
            PrlDevopsSlave slave = (PrlDevopsSlave) node;
            Jenkins jenkins = Jenkins.get();

            // Force-delete the VM (running or not) using ?force=true
            Cloud cloud = jenkins.clouds.getByName(slave.getCloudName());
            if (cloud instanceof PrlDevopsCloud) {
                try {
                    PrlDevopsApiClient client = ((PrlDevopsCloud) cloud).buildApiClient();
                    client.deleteVm(slave.getVmId());
                    LOG.info("[PrlDevops] Deleted VM " + slave.getVmId()
                            + " after build " + run.getFullDisplayName());
                } catch (PrlApiException e) {
                    LOG.log(Level.WARNING,
                            "[PrlDevops] Failed to delete VM " + slave.getVmId()
                                    + " after build completion: " + e.getMessage(), e);
                }
            }

            // Remove the node so Jenkins provisions a fresh clone for the next build
            try {
                jenkins.removeNode(slave);
                LOG.info("[PrlDevops] Removed node " + slave.getNodeName()
                        + " after build " + run.getFullDisplayName());
            } catch (IOException e) {
                LOG.log(Level.WARNING,
                        "[PrlDevops] Failed to remove node " + slave.getNodeName()
                                + " after build completion: " + e.getMessage(), e);
            }
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Parallels Devops Cloud";
        }

        @POST
        public ListBoxModel doFillConnectionModeItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel model = new ListBoxModel();
            for (ConnectionMode mode : ConnectionMode.values()) {
                model.add(mode.name(), mode.name());
            }
            return model;
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
