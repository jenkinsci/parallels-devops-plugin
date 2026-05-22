package com.parallels.jenkins;

import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Logger;

/**
 * {@link ComputerLauncher} that bootstraps a Jenkins agent on a Parallels DevOps
 * VM via SSH, delegating to {@link SSHLauncher} with configurable retry logic.
 *
 * <p>Constructed from the VM's dynamic IP (resolved at provision time) and the
 * {@link AgentTemplate} SSH settings. {@link SSHLauncher} handles copying
 * {@code agent.jar} and starting the remoting process.
 *
 * <p>Retry behaviour: if the SSH daemon is not yet up when {@code launch()} is
 * first called, the launcher retries up to {@code sshRetries} times, waiting
 * {@code sshRetryDelaySec} seconds between attempts, before marking the node
 * offline with a descriptive error.
 */
public class PrlDevopsComputerLauncher extends ComputerLauncher {

    private static final Logger LOGGER =
            Logger.getLogger(PrlDevopsComputerLauncher.class.getName());

    private final String vmIp;
    private final int sshPort;
    private final String sshCredentialsId;
    private final String javaPath;
    private final String jvmOptions;
    private final int sshRetries;
    private final int sshRetryDelaySec;
    private volatile boolean launchExhaustedRetries;

    public PrlDevopsComputerLauncher(String vmIp, AgentTemplate template) {
        this.vmIp = vmIp;
        this.sshPort = template.getSshPort();
        this.sshCredentialsId = template.getSshCredentialsId();
        this.javaPath = template.getJavaPath();
        this.jvmOptions = template.getJvmOptions();
        this.sshRetries = template.getSshRetries();
        this.sshRetryDelaySec = template.getSshRetryDelaySec();
    }

    // Package-private for tests
    String getVmIp() { return vmIp; }
    int getSshPort() { return sshPort; }
    String getSshCredentialsId() { return sshCredentialsId; }
    int getSshRetries() { return sshRetries; }
    int getSshRetryDelaySec() { return sshRetryDelaySec; }
    boolean hasExhaustedRetries() { return launchExhaustedRetries; }
    /** Simulates exhausted SSH retries in tests without triggering a real SSH attempt. */
    void markRetryExhausted() { this.launchExhaustedRetries = true; }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException {
        PrintStream log = listener.getLogger();
        if (launchExhaustedRetries) {
            throw new IOException("[PrlDevops] SSH retries already exhausted for VM " + vmIp
                    + ":" + sshPort + ". Waiting for retention cleanup.");
        }

        log.println("[PrlDevops] Connecting to VM " + vmIp + " via SSH"
                + " (port=" + sshPort
                + ", retries=" + sshRetries
                + ", retryDelay=" + sshRetryDelaySec + "s)");

        SSHLauncher sshLauncher = buildSshLauncher();
        try {
            sshLauncher.launch(computer, listener);
            launchExhaustedRetries = false;
            if (computer.isOnline()) {
                LOGGER.fine("[PrlDevops] SSH agent online for VM " + vmIp);
                return;
            }
            throw new IOException("[PrlDevops] SSH bootstrap finished without bringing node online for VM "
                    + vmIp + ":" + sshPort);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("[PrlDevops] SSH launch interrupted for VM " + vmIp, e);
        } catch (IOException | RuntimeException e) {
            launchExhaustedRetries = true;
            computer.setAcceptingTasks(false);
            String msg = "[PrlDevops] All " + sshRetries + " SSH attempts failed for VM " + vmIp
                    + ":" + sshPort + ". Waiting for retention cleanup.";
            log.println(msg);
            LOGGER.warning(msg);
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("[PrlDevops] SSH launch failed for VM " + vmIp, e);
        }
    }

    SSHLauncher buildSshLauncher() {
        SSHLauncher launcher = new SSHLauncher(vmIp, sshPort, sshCredentialsId);
        launcher.setSshHostKeyVerificationStrategy(new NonVerifyingKeyVerificationStrategy());
        launcher.setMaxNumRetries(sshRetries);
        launcher.setRetryWaitTime(sshRetryDelaySec);
        if (javaPath != null && !javaPath.isBlank() && !javaPath.equals("java")) {
            launcher.setJavaPath(javaPath);
        }
        if (jvmOptions != null && !jvmOptions.isBlank()) {
            launcher.setJvmOptions(jvmOptions);
        }
        return launcher;
    }
}
