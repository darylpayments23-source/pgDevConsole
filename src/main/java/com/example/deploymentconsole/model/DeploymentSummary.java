package com.example.deploymentconsole.model;

public record DeploymentSummary(
        String id,
        String environment,
        String folder,
        String status,
        int total,
        int successful,
        int failed,
        String startedAt,
        String completedAt
) {}
