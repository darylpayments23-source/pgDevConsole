package com.example.deploymentconsole.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/** A row of the {@code users} table. The password hash is never serialized to JSON. */
public record User(
        long id,
        String username,
        @JsonIgnore String passwordHash,
        Role role,
        boolean mustChangePassword,
        String createdBy,
        Instant createdAt,
        Instant lastLogin,
        boolean active,
        @JsonIgnore Instant passwordChangedAt,
        Instant resetRequestedAt
) {}
