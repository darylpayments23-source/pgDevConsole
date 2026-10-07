package com.example.deploymentconsole.config;

import com.example.deploymentconsole.model.AuthenticatedUser;
import com.example.deploymentconsole.service.AuthException;
import com.example.deploymentconsole.service.JwtTokenService;
import com.example.deploymentconsole.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Authenticates every {@code /api/**} request with a {@code Authorization: Bearer <jwt>} header.
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} and {@code POST /api/auth/forgot-password} are public.</li>
 *   <li>Missing/invalid/expired/revoked tokens and deactivated accounts get {@code 401} JSON.</li>
 *   <li>While a user must change their password, only {@code /api/auth/me}, {@code /api/auth/change-password}
 *       and {@code /api/auth/logout} are allowed ({@code 403 PASSWORD_CHANGE_REQUIRED}).</li>
 * </ul>
 * The authenticated user is stored as a request attribute, see {@link AuthenticatedUser#from}.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final Set<String> PUBLIC = Set.of("/api/auth/login", "/api/auth/forgot-password");
    private static final Set<String> ALLOWED_WHILE_PASSWORD_CHANGE_REQUIRED =
            Set.of("/api/auth/me", "/api/auth/change-password", "/api/auth/logout");

    private final JwtTokenService tokens;
    private final UserService users;
    private final ObjectMapper mapper;

    public JwtAuthFilter(JwtTokenService tokens, UserService users, ObjectMapper mapper) {
        this.tokens = tokens;
        this.users = users;
        this.mapper = mapper;
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        String p = ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = path(request);
        return !(p.equals("/api") || p.startsWith("/api/"))
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || PUBLIC.contains(p);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            writeError(response, mapper, 401, "UNAUTHORIZED", "Please log in.");
            return;
        }
        AuthenticatedUser user;
        try {
            user = users.resolve(tokens.validate(header.substring(7).trim()));
        } catch (AuthException e) {
            writeError(response, mapper, e.getStatus(), e.getCode(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.error("Authentication failed: {}", e.getMessage());
            writeError(response, mapper, 503, "AUTH_UNAVAILABLE", "Authentication service unavailable.");
            return;
        }
        if (user.mustChangePassword() && !ALLOWED_WHILE_PASSWORD_CHANGE_REQUIRED.contains(path(request))) {
            writeError(response, mapper, 403, "PASSWORD_CHANGE_REQUIRED", "You must change your password first.");
            return;
        }
        request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    static void writeError(HttpServletResponse response, ObjectMapper mapper, int status, String code, String message)
            throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
