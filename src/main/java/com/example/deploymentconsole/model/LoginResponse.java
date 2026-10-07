package com.example.deploymentconsole.model;

import java.time.Instant;

public record LoginResponse(String token, String username, Role role, boolean mustChangePassword, Instant expiresAt) {}
