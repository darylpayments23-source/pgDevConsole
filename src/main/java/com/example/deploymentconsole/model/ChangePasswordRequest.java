package com.example.deploymentconsole.model;

import jakarta.validation.constraints.NotBlank;

/** {@code oldPassword} may be omitted while the user is forced to change a temporary password. */
public record ChangePasswordRequest(String oldPassword, @NotBlank String newPassword) {}
