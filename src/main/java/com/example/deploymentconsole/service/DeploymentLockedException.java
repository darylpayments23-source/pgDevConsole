package com.example.deploymentconsole.service;

/** Thrown when a deployment cannot start because another deployment holds the lock for the environment. */
public class DeploymentLockedException extends RuntimeException {
    private final String environment;
    private final DeploymentLockService.LockInfo lockInfo;   // may be null if the lock was released in the meantime

    public DeploymentLockedException(String environment, DeploymentLockService.LockInfo lockInfo) {
        super(buildMessage(environment, lockInfo));
        this.environment = environment;
        this.lockInfo = lockInfo;
    }

    public String getEnvironment() { return environment; }
    public DeploymentLockService.LockInfo getLockInfo() { return lockInfo; }

    private static String buildMessage(String environment, DeploymentLockService.LockInfo info) {
        String env = environment == null ? "environment" : environment.toUpperCase(java.util.Locale.ROOT);
        if (info == null) return env + " is locked by another deployment. Please retry shortly.";
        return env + " is locked by deployment " + info.deploymentId()
                + " (lock expires in " + info.remainingSeconds() + "s).";
    }
}
