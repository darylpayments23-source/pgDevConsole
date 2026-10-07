package com.example.deploymentconsole.model;

import jakarta.servlet.http.HttpServletRequest;

/** The caller of the current request, as established by {@code JwtAuthFilter}. */
public record AuthenticatedUser(String username, Role role, boolean mustChangePassword, String tokenId,
                                java.time.Instant tokenExpiresAt) {

    public static final String REQUEST_ATTRIBUTE = AuthenticatedUser.class.getName();

    /** The authenticated user of {@code request}, or null if the request is anonymous. */
    public static AuthenticatedUser from(HttpServletRequest request) {
        return (AuthenticatedUser) request.getAttribute(REQUEST_ATTRIBUTE);
    }

    public boolean hasRole(Role... roles) {
        for (Role r : roles) if (r == role) return true;
        return false;
    }
}
