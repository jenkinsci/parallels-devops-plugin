package com.parallels.jenkins;

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;

/**
 * Cloud computer that marks a provisioned agent as one-shot after its first task.
 */
public class PrlDevopsComputer extends AbstractCloudComputer<PrlDevopsAgent> {

    private volatile boolean taskAccepted;

    public PrlDevopsComputer(PrlDevopsAgent agent) {
        super(agent);
    }

    public boolean hasAcceptedTask() {
        return taskAccepted;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        taskAccepted = true;
        setAcceptingTasks(false);
    }
}