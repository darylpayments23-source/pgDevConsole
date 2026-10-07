package com.example.deploymentconsole.config;

import com.example.deploymentconsole.model.Role;

import java.lang.annotation.*;

/**
 * Marks a controller (or a single handler method) as requiring a logged-in user.
 * With {@code roles} set, the user must also have one of those roles (otherwise 403).
 * A method-level annotation overrides the class-level one.
 *
 * <p>Authentication itself (validating the JWT) is done by {@link JwtAuthFilter} for every {@code /api/**}
 * request; this annotation is enforced by {@link RequireAuthInterceptor}.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAuth {
    /** Allowed roles; empty = any authenticated user. */
    Role[] roles() default {};
}
