package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.dto.ExecuteRequest;
import com.parallels.jenkins.api.dto.ExecuteResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Node;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Jenkins Build Step that executes a shell command on the provisioned
 * Parallels DevOps VM via {@code PUT /api/v1/machines/{id}/execute}.
 *
 * <p>Add it to a job via <strong>Build Steps → Execute on Parallels VM</strong>.
 *
 * <p>The build fails if the command exits with a non-zero exit code.
 */
public class PrlDevopsBuilder extends Builder {

    private final String command;
    private List<EnvVar> environmentVariables = Collections.emptyList();

    @DataBoundConstructor
    public PrlDevopsBuilder(String command) {
        this.command = command;
    }

    public String getCommand() { return command; }
    public List<EnvVar> getEnvironmentVariables() { return environmentVariables; }

    @DataBoundSetter
    public void setEnvironmentVariables(List<EnvVar> environmentVariables) {
        this.environmentVariables = environmentVariables != null
                ? environmentVariables : Collections.emptyList();
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws IOException {
        PrintStream log = listener.getLogger();

        Node node = build.getBuiltOn();
        if (!(node instanceof PrlDevopsSlave)) {
            log.println("[PrlDevops] ERROR: This build step requires a Parallels DevOps agent."
                    + " Current node: " + (node != null ? node.getNodeName() : "null"));
            return false;
        }
        PrlDevopsSlave slave = (PrlDevopsSlave) node;

        hudson.slaves.Cloud cloud = Jenkins.get().clouds.getByName(slave.getCloudName());
        if (!(cloud instanceof PrlDevopsCloud)) {
            log.println("[PrlDevops] ERROR: Cloud '" + slave.getCloudName() + "' not found.");
            return false;
        }

        PrlDevopsApiClient client;
        try {
            client = ((PrlDevopsCloud) cloud).buildApiClient();
        } catch (PrlApiException e) {
            log.println("[PrlDevops] ERROR: Cannot build API client: " + e.getMessage());
            return false;
        }

        Map<String, String> envMap = new LinkedHashMap<>();
        for (EnvVar ev : environmentVariables) {
            envMap.put(ev.getEnvKey(), ev.getEnvValue());
        }

        String vmUser = slave.getTemplate().getVmUser();
        log.println("[PrlDevops] Executing on VM " + slave.getVmId()
                + " as user '" + vmUser + "': " + command);

        ExecuteRequest request = new ExecuteRequest(command, vmUser, envMap);
        try {
            ExecuteResponse response = client.executeCommand(slave.getVmId(), request);
            if (response.getStdout() != null && !response.getStdout().isBlank()) {
                log.println(response.getStdout());
            }
            if (response.getExitCode() != 0) {
                log.println("[PrlDevops] Command exited with code " + response.getExitCode());
                return false;
            }
            return true;
        } catch (PrlApiException e) {
            log.println("[PrlDevops] ERROR executing command: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Key=Value pair for environment variables in the UI
    // -------------------------------------------------------------------------

    public static final class EnvVar {
        private final String envKey;
        private final String envValue;

        @DataBoundConstructor
        public EnvVar(String envKey, String envValue) {
            this.envKey = envKey;
            this.envValue = envValue;
        }

        public String getEnvKey() { return envKey; }
        public String getEnvValue() { return envValue; }
    }

    // -------------------------------------------------------------------------
    // Descriptor
    // -------------------------------------------------------------------------

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Execute on Parallels DevOps VM";
        }
    }
}
