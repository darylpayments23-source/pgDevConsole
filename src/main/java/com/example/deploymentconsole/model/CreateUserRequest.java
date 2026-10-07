package com.example.deploymentconsole.model;

import jakarta.validation.constraints.NotBlank;

/** {@code role} is optional and defaults to VIEWER. */
public record CreateUserRequest(@NotBlank String username, String role) {}
