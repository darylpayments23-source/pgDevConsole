package com.example.deploymentconsole.config;

import com.example.deploymentconsole.model.AuthenticatedUser;
import com.example.deploymentconsole.model.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/** Enforces {@link RequireAuth} on controllers and handler methods. */
@Component
public class RequireAuthInterceptor implements HandlerInterceptor {
    private final ObjectMapper mapper;

    public RequireAuthInterceptor(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RequireAuth rule = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequireAuth.class);
        if (rule == null) rule = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequireAuth.class);
        if (rule == null) return true;

        AuthenticatedUser user = AuthenticatedUser.from(request);
        if (user == null) {
            JwtAuthFilter.writeError(response, mapper, 401, "UNAUTHORIZED", "Please log in.");
            return false;
        }
        Role[] roles = rule.roles();
        if (roles.length > 0 && !user.hasRole(roles)) {
            JwtAuthFilter.writeError(response, mapper, 403, "FORBIDDEN",
                    "This action requires role " + String.join(" or ", Arrays.stream(roles).map(Enum::name).toList()) + ".");
            return false;
        }
        return true;
    }
}
