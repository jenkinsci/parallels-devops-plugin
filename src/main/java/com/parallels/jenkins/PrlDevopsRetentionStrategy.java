package com.parallels.jenkins;

import com.parallels.jenkins.api.PrlDevopsApiClient;
import com.parallels.jenkins.api.exception.PrlApiException;
import hudson.slaves.Cloud;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-shot retention strategy for provisioned Parallels agents.
 */
public class PrlDevopsRetentionStrategy extends RetentionStrategy<PrlDevopsComputer> {

    private static final Logger LOGGER = Logger.getLogger(PrlDevopsRetentionStrategy.class.getName());

    @Override
    public long check(PrlDevopsComputer computer) {
        if (!computer.hasAcceptedTask()) {
            if (hasExhaustedBootstrapRetries(computer)) {
                computer.setAcceptingTasks(false);
                tearDown(computer, "SSH bootstrap exhausted configured retries");
                return 1;
            }
            if (!computer.isOnline() && !computer.isConnecting()) {
                computer.connect(false);
            }
            return 1;
        }

        if (!computer.isIdle()) {
            return 1;
        }

        computer.setAcceptingTasks(false);
        tearDown(computer, "Completed one-shot Parallels build");
        return 1;
    }

    @Override
    public boolean isAcceptingTasks(PrlDevopsComputer computer) {
        return !computer.hasAcceptedTask();
    }

    @Override
    public void start(PrlDevopsComputer computer) {
        if (!hasExhaustedBootstrapRetries(computer)) {
            computer.connect(false);
        }
    }

    private boolean hasExhaustedBootstrapRetries(PrlDevopsComputer computer) {
        PrlDevopsAgent agent = computer.getNode();
        return agent != null
                && agent.getLauncher() instanceof PrlDevopsComputerLauncher launcher
                && launcher.hasExhaustedRetries();
    }

    private void tearDown(PrlDevopsComputer computer, String offlineReason) {
        PrlDevopsAgent agent = computer.getNode();
        if (agent == null) {
            return;
        }

        try {
            computer.disconnect(new OfflineCause.ByCLI(offlineReason))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.FINE, "[PrlDevops] Interrupted while disconnecting " + agent.getNodeName(), e);
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.log(Level.FINE,
                    "[PrlDevops] Could not confirm clean disconnect for " + agent.getNodeName()
                            + " before VM deletion.", e);
        }

        // Mark terminated before calling deleteVm() so that the subsequent
        // removeNode() → _terminate() callback is a guaranteed no-op.
        if (!agent.markTerminated()) {
            LOGGER.fine("[PrlDevops] tearDown: agent " + agent.getNodeName()
                    + " already marked for termination — skipping duplicate cleanup.");
            return;
        }

        Jenkins jenkins = Jenkins.get();
        Cloud cloud = jenkins.clouds.getByName(agent.getCloudName());
        if (cloud instanceof PrlDevopsCloud prlCloud) {
            try {
                PrlDevopsApiClient client = prlCloud.buildApiClient();
                client.deleteVm(agent.getVmId());
                LOGGER.fine("[PrlDevops] Deleted VM " + agent.getVmId()
                        + " after one-shot execution on " + agent.getNodeName());
            } catch (PrlApiException e) {
                LOGGER.log(Level.WARNING,
                        "[PrlDevops] Failed to delete VM " + agent.getVmId()
                                + " during retention cleanup: " + e.getMessage(), e);
            }
        }

        try {
            jenkins.removeNode(agent);
            LOGGER.fine("[PrlDevops] Removed node " + agent.getNodeName() + " after one-shot execution");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "[PrlDevops] Failed to remove node " + agent.getNodeName()
                            + " during retention cleanup: " + e.getMessage(), e);
        }
    }
}
