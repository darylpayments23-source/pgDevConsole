package com.example.deploymentconsole.model;

import jakarta.validation.constraints.NotBlank;

public record DeploymentRequest(
        @NotBlank String folder,
        @NotBlank String environment,
        String commitMode,
        Boolean confirmModified   // true = operator accepted deploying scripts changed since their last successful run
) {}
