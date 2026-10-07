package com.example.deploymentconsole.controller;

import com.example.deploymentconsole.config.RequireAuth;
import com.example.deploymentconsole.model.AuthenticatedUser;
import com.example.deploymentconsole.model.CreateUserRequest;
import com.example.deploymentconsole.model.Role;
import com.example.deploymentconsole.model.UserView;
import com.example.deploymentconsole.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** User administration. SuperAdmin (role ADMIN) only. */
@RestController
@RequestMapping("/api/admin/users")
@RequireAuth(roles = Role.ADMIN)
public class AdminController {
    private final UserService users;

    public AdminController(UserService users) { this.users = users; }

    /** Creates a user with a temporary password (returned once, in this response). */
    @PostMapping
    public ResponseEntity<UserService.TemporaryPassword> create(@Valid @RequestBody CreateUserRequest req,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(201).body(users.createUser(AuthenticatedUser.from(request).username(), req));
    }

    @GetMapping
    public List<UserView> list() {
        return users.listUsers().stream().map(UserView::of).toList();
    }

    /** Deactivates (does not delete) the user; their sessions stop working immediately. */
    @DeleteMapping("/{username}")
    public Map<String, Object> deactivate(@PathVariable String username, HttpServletRequest request) {
        users.deactivate(AuthenticatedUser.from(request).username(), username);
        return Map.of("success", true, "username", username, "active", false);
    }

    /** Re-enables a deactivated user. */
    @PostMapping("/{username}/activate")
    public Map<String, Object> activate(@PathVariable String username, HttpServletRequest request) {
        users.activate(AuthenticatedUser.from(request).username(), username);
        return Map.of("success", true, "username", username, "active", true);
    }

    /** Generates a new temporary password; the user must change it at next login. */
    @PostMapping("/{username}/reset-password")
    public UserService.TemporaryPassword resetPassword(@PathVariable String username, HttpServletRequest request) {
        return users.resetPassword(AuthenticatedUser.from(request).username(), username);
    }
}
