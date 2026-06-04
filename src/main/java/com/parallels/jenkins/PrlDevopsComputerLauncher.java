package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.dto.ExecuteRequest;
import com.parallels.jenkins.api.dto.ExecuteResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * {@link JNLPLauncher} that bootstraps a Jenkins inbound agent on a Parallels DevOps
 * VM using the execute API. The agent connects TO the Jenkins controller (not SSH).
 *
 * <p>This launcher uses the Parallels DevOps executeCommand API to:
 * <ol>
 *   <li>Download agent.jar from Jenkins controller</li>
 *   <li>Start the inbound agent process that connects back to Jenkins</li>
 * </ol>
 *
 * <p>The VM initiates the connection to Jenkins, eliminating the need for:
 * <ul>
 *   <li>SSH server on the VM</li>
 *   <li>Direct network routing from Jenkins to VM private IP</li>
 *   <li>SSH credentials management</li>
 * </ul>
 *
 * <p>The agent establishes a persistent TCP/WebSocket connection to Jenkins.
 * All build execution happens over this remoting channel, not via executeCommand.
 */
public class PrlDevopsComputerLauncher extends JNLPLauncher {

    private static final Logger LOGGER =
            Logger.getLogger(PrlDevopsComputerLauncher.class.getName());
    private static final long serialVersionUID = 1L;

    private final String cloudName;
    private final String vmId;
    private final String vmUser;
    private transient PrlDevopsApiClient apiClient;  // transient: cannot serialize HttpClient
    private final String javaPath;
    private final String jvmOptions;
    private final int agentConnectionTimeoutSec;
    private volatile boolean launchFailed;

    public PrlDevopsComputerLauncher(String cloudName, String vmId, String vmUser, 
                                    PrlDevopsApiClient apiClient, AgentTemplate template) {
        super();  // Call JNLPLauncher constructor
        this.cloudName = cloudName;
        this.vmId = vmId;
        this.vmUser = vmUser;
        this.apiClient = apiClient;
        this.javaPath = template.getJavaPath();
        this.jvmOptions = template.getJvmOptions();
        this.agentConnectionTimeoutSec = template.getAgentConnectionTimeoutSec();
    }

    /**
     * Get or recreate the API client. The apiClient is marked transient because HttpClient
     * cannot be serialized. When Jenkins deserializes this launcher, we need to recreate it.
     */
    private PrlDevopsApiClient getApiClient() throws IOException {
        if (apiClient != null) {
            return apiClient;
        }
        
        // Recreate from cloud configuration
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            throw new IOException("[PrlDevops] Jenkins instance not available");
        }
        
        Cloud cloud = jenkins.getCloud(cloudName);
        if (!(cloud instanceof PrlDevopsCloud)) {
            throw new IOException("[PrlDevops] Cloud '" + cloudName + "' not found or not a PrlDevopsCloud");
        }
        
        try {
            apiClient = ((PrlDevopsCloud) cloud).buildApiClient();
            return apiClient;
        } catch (PrlApiException e) {
            throw new IOException("[PrlDevops] Failed to recreate API client for cloud '" + cloudName + "': " 
                    + e.getMessage(), e);
        }
    }

    // Package-private for tests
    String getVmId() { return vmId; }
    String getVmUser() { return vmUser; }
    int getAgentConnectionTimeoutSec() { return agentConnectionTimeoutSec; }
    boolean hasLaunchFailed() { return launchFailed; }
    /** Simulates launch failure in tests without triggering real API calls. */
    void markLaunchFailed() { this.launchFailed = true; }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) {
        PrintStream log = listener.getLogger();
        
        try {
            if (launchFailed) {
                throw new IOException("[PrlDevops] Agent launch already failed for VM " + vmId 
                        + ". Waiting for retention cleanup.");
            }

            String agentName = computer.getName();
            String jenkinsUrl = getJenkinsUrl();
            String secret = computer.getJnlpMac();
            
            log.println("[PrlDevops] Bootstrapping inbound agent on VM " + vmId);
            log.println("[PrlDevops] Agent will connect to: " + jenkinsUrl);
            
            // Step 0: Verify VM is ready to execute commands (double-check readiness)
            log.println("[PrlDevops] Verifying VM readiness before agent bootstrap...");
            if (!waitForVmExecuteReady(log, 60)) {
                throw new IOException("[PrlDevops] VM " + vmId + " is not ready to execute commands after 60 seconds");
            }
            log.println("[PrlDevops] VM is ready to execute commands");
            
            // Step 1: Download agent.jar from Jenkins controller
            log.println("[PrlDevops] Downloading agent.jar via execute API...");
            String downloadCmd = buildDownloadCommand(jenkinsUrl);
            log.println("[PrlDevops] Download command: " + downloadCmd);
            
            ExecuteRequest downloadRequest = new ExecuteRequest(downloadCmd, vmUser, Collections.emptyMap());
            ExecuteResponse downloadResp = getApiClient().executeCommand(vmId, downloadRequest);
            
            log.println("[PrlDevops] Download exit code: " + downloadResp.getExitCode());
            if (downloadResp.getStdout() != null && !downloadResp.getStdout().isBlank()) {
                log.println("[PrlDevops] Download output: " + downloadResp.getStdout());
            }
            
            if (downloadResp.getExitCode() != 0) {
                String errorMsg = "[PrlDevops] Failed to download agent.jar. Exit code: " + downloadResp.getExitCode();
                if (downloadResp.getExitCode() == 127) {
                    errorMsg += "\n[PrlDevops] No download tools found in VM. Install wget, curl, or python.";
                } else if (downloadResp.getExitCode() == 4 || downloadResp.getExitCode() == 7) {
                    errorMsg += "\n[PrlDevops] Network error. Check:\n" +
                                "  - Jenkins URL is accessible from VM: " + jenkinsUrl + "\n" +
                                "  - Jenkins is listening on 0.0.0.0 (not just localhost)\n" +
                                "  - Firewall allows connections to Jenkins port";
                }
                errorMsg += "\nOutput: " + downloadResp.getStdout();
                throw new IOException(errorMsg);
            }
            
            // Verify agent.jar was downloaded and has correct size
            log.println("[PrlDevops] Verifying agent.jar download...");
            String verifyCmd = "ls -lh /tmp/agent.jar && wc -c /tmp/agent.jar";
            ExecuteResponse verifyResp = getApiClient().executeCommand(vmId,
                    new ExecuteRequest(verifyCmd, vmUser, Collections.emptyMap()));
            log.println("[PrlDevops] agent.jar verification: " + verifyResp.getStdout());
            
            if (verifyResp.getExitCode() != 0 || !verifyResp.getStdout().contains("/tmp/agent.jar")) {
                throw new IOException("[PrlDevops] agent.jar file not found or download incomplete");
            }
            log.println("[PrlDevops] agent.jar downloaded successfully");
            
            // Step 2: Start inbound agent process (connects TO Jenkins)
            log.println("[PrlDevops] Starting inbound agent process...");
            String javaCmd = buildJavaPath();
            String jvmOpts = jvmOptions != null && !jvmOptions.isBlank() ? jvmOptions + " " : "";
            
            // Use sh -c with proper backgrounding that works without TTY
            // Redirect stdin from /dev/null to avoid "Inappropriate ioctl" errors from nohup
            // Use -webSocket flag to avoid X-Instance-Identity issues in development mode
            String agentCmd = String.format(
                    "sh -c '%s %s-jar /tmp/agent.jar -url \"%s\" -secret \"%s\" -name \"%s\" -workDir /tmp/jenkins -webSocket </dev/null >/tmp/agent.log 2>&1 & echo Agent_PID=$!'",
                    javaCmd, jvmOpts, jenkinsUrl, secret, agentName);
            log.println("[PrlDevops] Agent command: " + agentCmd.replace(secret, "***SECRET***"));
            
            ExecuteResponse startResp = getApiClient().executeCommand(vmId,
                    new ExecuteRequest(agentCmd, vmUser, Collections.emptyMap()));
            
            log.println("[PrlDevops] Agent start exit code: " + startResp.getExitCode());
            if (startResp.getStdout() != null && !startResp.getStdout().isBlank()) {
                log.println("[PrlDevops] Agent start output: " + startResp.getStdout());
            }
            
            if (startResp.getExitCode() != 0) {
                throw new IOException("[PrlDevops] Failed to start agent process. Exit code: " 
                        + startResp.getExitCode() + "\nOutput: " + startResp.getStdout());
            }
            
            // Give agent a moment to start and check if process is running
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("[PrlDevops] Agent startup interrupted", e);
            }
            ExecuteResponse psResp = getApiClient().executeCommand(vmId,
                    new ExecuteRequest("ps aux | grep agent.jar | grep -v grep || echo 'No agent process found'", 
                            vmUser, Collections.emptyMap()));
            log.println("[PrlDevops] Agent process check: " + psResp.getStdout());
            
            log.println("[PrlDevops] Agent process started. Waiting for inbound connection...");
            
            // Step 3: Wait for agent to connect (Jenkins will update computer status)
            waitForConnection(computer, listener, agentConnectionTimeoutSec);
            
            if (computer.isOnline()) {
                log.println("[PrlDevops] Inbound agent connected successfully!");
                LOGGER.fine("[PrlDevops] Inbound agent online for VM " + vmId);
                launchFailed = false;
            } else {
                throw new IOException("[PrlDevops] Agent process started but failed to connect within " 
                        + agentConnectionTimeoutSec + " seconds. Check /tmp/agent.log on the VM.");
            }
            
        } catch (PrlApiException | IOException e) {
            launchFailed = true;
            computer.setAcceptingTasks(false);
            String msg = "[PrlDevops] Inbound agent launch failed for VM " + vmId + ": " + e.getMessage();
            log.println(msg);
            LOGGER.warning(msg);
            
            // Try to get agent log for debugging
            try {
                log.println("[PrlDevops] Attempting to retrieve agent log...");
                ExecuteResponse logResp = getApiClient().executeCommand(vmId,
                        new ExecuteRequest("tail -50 /tmp/agent.log 2>/dev/null || echo 'No log file'", 
                                vmUser, Collections.emptyMap()));
                log.println("[PrlDevops] Agent log (last 50 lines):");
                log.println(logResp.getStdout());
            } catch (PrlApiException | IOException logErr) {
                log.println("[PrlDevops] Could not retrieve agent log: " + logErr.getMessage());
            }
            
            // Cannot throw IOException from JNLPLauncher.launch()
            // The computer will remain offline and retention strategy will clean it up
        }
    }

    /**
     * Waits for the VM to be ready to execute commands via execute API.
     * Retries a simple echo command until it succeeds or timeout.
     * 
     * @param log Output stream for logging
     * @param timeoutSec Maximum time to wait in seconds
     * @return true if VM is ready, false if timeout
     */
    private boolean waitForVmExecuteReady(PrintStream log, int timeoutSec) {
        long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
        int attempt = 0;
        
        log.println("[PrlDevops] Starting VM readiness probe (timeout: " + timeoutSec + "s)...");
        
        while (System.currentTimeMillis() < deadline) {
            try {
                attempt++;
                ExecuteResponse probe = getApiClient().executeCommand(vmId,
                        new ExecuteRequest("echo prl-ready", vmUser, Collections.emptyMap()));
                
                log.println("[PrlDevops] Readiness probe attempt " + attempt + " - exit code: " + probe.getExitCode());
                
                if (probe.getExitCode() == 0) {
                    log.println("[PrlDevops] VM execute API ready after " + attempt + " attempts!");
                    LOGGER.fine("[PrlDevops] VM " + vmId + " execute API ready after " + attempt + " attempts");
                    return true;
                }
                
                if (probe.getStdout() != null && !probe.getStdout().isBlank()) {
                    log.println("[PrlDevops] Probe stdout: " + probe.getStdout());
                }
            } catch (PrlApiException | IOException e) {
                // Execute API not ready yet, will retry
                log.println("[PrlDevops] Readiness probe attempt " + attempt + " failed: " + e.getMessage());
                LOGGER.fine("[PrlDevops] VM " + vmId + " execute probe failed (attempt " + attempt + "): " + e.getMessage());
            }
            
            long remaining = (deadline - System.currentTimeMillis()) / 1000;
            if (remaining <= 0) {
                log.println("[PrlDevops] VM readiness timeout - execute API not ready after " + attempt + " attempts");
                break;
            }
            
            if (attempt == 1 || attempt % 6 == 0) {  // Log every ~30 seconds
                log.println("[PrlDevops] Waiting for VM execute API to be ready... (" + remaining + "s remaining)");
            }
            
            try {
                Thread.sleep(5000);  // Check every 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.println("[PrlDevops] Readiness check interrupted");
                return false;
            }
        }
        
        log.println("[PrlDevops] VM execute API did not become ready within " + timeoutSec + " seconds");
        return false;
    }

    /**
     * Waits for the 
            
            throw new IOException("[PrlDevops] Inbound agent launch failed for VM " + vmId, e);
        }
    }

    /**
     * Waits for the inbound agent to establish connection to Jenkins.
     * The computer status is updated by Jenkins when the agent connects.
     */
    private void waitForConnection(SlaveComputer computer, TaskListener listener, int timeoutSec) 
            throws IOException {
        PrintStream log = listener.getLogger();
        long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
        int attempt = 0;
        
        while (System.currentTimeMillis() < deadline) {
            if (computer.isOnline()) {
                return;
            }
            
            if (attempt % 6 == 0) {  // Log every ~30 seconds
                long remaining = (deadline - System.currentTimeMillis()) / 1000;
                log.println("[PrlDevops] Waiting for agent connection... (" + remaining + "s remaining)");
            }
            
            try {
                Thread.sleep(5000);  // Check every 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("[PrlDevops] Agent connection wait interrupted", e);
            }
            attempt++;
        }
    }

    /**
     * Builds the full path to the Java executable based on configuration.
     */
    private String buildJavaPath() {
        if (javaPath == null || javaPath.isBlank() || javaPath.equals("java")) {
            return "java";
        }
        // If custom path provided, use it (e.g., /usr/lib/jvm/java-17/bin/java)
        return javaPath;
    }

    /**
     * Gets the Jenkins root URL that agents should connect to.
     */
    private String getJenkinsUrl() throws IOException {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            throw new IOException("[PrlDevops] Jenkins instance not available");
        }
        
        String rootUrl = jenkins.getRootUrl();
        if (rootUrl == null || rootUrl.isBlank()) {
            throw new IOException("[PrlDevops] Jenkins URL is not configured. " +
                    "Please set it in Manage Jenkins > System Configuration > Jenkins Location");
        }
        
        // Remove trailing slash
        return rootUrl.endsWith("/") ? rootUrl.substring(0, rootUrl.length() - 1) : rootUrl;
    }

    /**
     * Builds download command with fallback support for multiple download tools.
     * Tries: wget → curl → python3 → python2 → fetch (BSD)
     * Uses simple shell OR chaining (||) that works in any POSIX shell.
     */
    private String buildDownloadCommand(String jenkinsUrl) {
        String url = jenkinsUrl + "/jnlpJars/agent.jar";
        String target = "/tmp/agent.jar";
        
        // Try wget (most common in Linux), then curl, then python variants
        // Using || for fallback - if command fails, try next one
        // Removed quotes around paths - simpler and works in execute API
        return String.format(
            "wget -O %s %s 2>/dev/null || " +
            "curl -sS -f -L -o %s %s 2>/dev/null || " +
            "python3 -c \"import urllib.request; urllib.request.urlretrieve('%s','%s')\" 2>/dev/null || " +
            "python -c \"import urllib; urllib.urlretrieve('%s','%s')\" 2>/dev/null || " +
            "fetch -o %s %s 2>/dev/null || " +
            "{ echo 'ERROR: No download tool found. Please install wget, curl, or python.' >&2; exit 127; }",
            target, url,      // wget
            target, url,      // curl
            url, target,      // python3
            url, target,      // python2
            target, url       // fetch (BSD/FreeBSD)
        );
    }
}
