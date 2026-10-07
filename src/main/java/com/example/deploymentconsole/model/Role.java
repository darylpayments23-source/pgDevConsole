package com.example.deploymentconsole.model;

import java.util.Locale;

/**
 * User roles.
 * <ul>
 *   <li>{@code ADMIN}    – SuperAdmin: manages users and can deploy.</li>
 *   <li>{@code DEPLOYER} – can scan and execute deployments.</li>
 *   <li>{@code VIEWER}   – read-only: can scan and watch deployments/history, cannot execute.</li>
 * </ul>
 */
public enum Role {
    ADMIN, DEPLOYER, VIEWER;

    /** Parses a role name case-insensitively; null/blank means the default role (VIEWER). */
    public static Role parse(String value) {
        if (value == null || value.isBlank()) return VIEWER;
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role '" + value + "'. Allowed: ADMIN, DEPLOYER, VIEWER");
        }
    }
}
