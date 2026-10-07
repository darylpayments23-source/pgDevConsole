package com.example.deploymentconsole.model;

import java.time.Instant;

/** What the admin API exposes about a user (never the password hash). */
public record UserView(
        String username,
        Role role,
        boolean active,
        boolean mustChangePassword,
        String createdBy,
        Instant createdAt,
        Instant lastLogin,
        Instant resetRequestedAt
) {
    public static UserView of(User u) {
        return new UserView(u.username(), u.role(), u.active(), u.mustChangePassword(), u.createdBy(),
                u.createdAt(), u.lastLogin(), u.resetRequestedAt());
    }
}
