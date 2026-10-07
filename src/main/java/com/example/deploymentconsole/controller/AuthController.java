package com.example.deploymentconsole.controller;

import com.example.deploymentconsole.config.RequireAuth;
import com.example.deploymentconsole.model.*;
import com.example.deploymentconsole.service.JwtTokenService;
import com.example.deploymentconsole.service.PasswordService;
import com.example.deploymentconsole.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService users;
    private final JwtTokenService tokens;
    private final PasswordService passwords;

    public AuthController(UserService users, JwtTokenService tokens, PasswordService passwords) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
    }

    /** Public. Returns {token, username, role, mustChangePassword, expiresAt}. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return users.authenticate(req.username(), req.password());
    }

    /**
     * Changes the caller's password. {@code oldPassword} is not needed while the account must change its
     * temporary password. Returns a fresh login because the change ends all earlier sessions.
     */
    @RequireAuth
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@Valid @RequestBody ChangePasswordRequest req, HttpServletRequest request) {
        AuthenticatedUser me = AuthenticatedUser.from(request);
        LoginResponse fresh = users.changePassword(me, req);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "Password changed.");
        body.put("token", fresh.token());
        body.put("username", fresh.username());
        body.put("role", fresh.role());
        body.put("mustChangePassword", fresh.mustChangePassword());
        body.put("expiresAt", fresh.expiresAt());
        return body;
    }

    /** The current user: {username, role, mustChangePassword, expiresAt, minPasswordLength}. */
    @RequireAuth
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        AuthenticatedUser me = AuthenticatedUser.from(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", me.username());
        body.put("role", me.role());
        body.put("mustChangePassword", me.mustChangePassword());
        body.put("expiresAt", me.tokenExpiresAt());
        body.put("minPasswordLength", passwords.getMinLength());
        return body;
    }

    /** Ends the session: the token is revoked server-side (the browser also drops it). */
    @RequireAuth
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        AuthenticatedUser me = AuthenticatedUser.from(request);
        tokens.revoke(me.tokenId(), me.tokenExpiresAt());
        log.info("AUDIT logout user={}", me.username());
        return Map.of("success", true);
    }

    /**
     * Public. "Forgot password?" sends the request to the SuperAdmin (shown in the admin user list).
     * Always answers the same way so it cannot be used to discover usernames.
     */
    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@RequestBody(required = false) Map<String, String> body) {
        users.requestPasswordReset(body == null ? null : body.get("username"));
        return Map.of("success", true,
                "message", "If the account exists, your administrator has been notified and will send you a temporary password.");
    }
}
