package com.example.deploymentconsole.service;

import com.example.deploymentconsole.config.AppProperties;
import com.example.deploymentconsole.model.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * User accounts: bootstrap SuperAdmin, login, password changes and user administration.
 * Users are stored in the history database (table {@code users}, see schema-auth.sql), accessed with plain
 * JDBC like the other services of this application.
 */
@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");
    private static final String COLUMNS = """
            id, username, password_hash, role, must_change_password, created_by, created_at,
            last_login, is_active, password_changed_at, reset_requested_at""";

    /** Result of creating a user or resetting a password: the one-time temporary password to hand over. */
    public record TemporaryPassword(String username, Role role, String temporaryPassword) {}

    private final AppProperties props;
    private final PasswordService passwords;
    private final JwtTokenService tokens;
    private volatile boolean initialized;

    public UserService(AppProperties props, PasswordService passwords, JwtTokenService tokens) {
        this.props = props;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    private Connection connect() throws SQLException {
        var e = props.getExecution();
        return DriverManager.getConnection(e.getHistoryDb(), e.getHistoryUsername(), e.getHistoryPassword());
    }

    // ------------------------------------------------------------------------------------------------
    // Startup: schema + SuperAdmin
    // ------------------------------------------------------------------------------------------------

    /** Creates the users table and the SuperAdmin account. Retried lazily if the database is down at startup. */
    @PostConstruct
    void init() {
        try {
            ensureInitialized();
        } catch (RuntimeException e) {
            log.error("User store not initialized yet (will retry on first login): {}", e.getMessage());
        }
    }

    private void ensureInitialized() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            try (Connection c = connect()) {
                applySchema(c);
                bootstrapSuperAdmin(c);
                initialized = true;
            } catch (SQLException | IOException e) {
                throw new IllegalStateException("Could not initialize the user store: " + e.getMessage(), e);
            }
        }
    }

    private void applySchema(Connection c) throws IOException, SQLException {
        String sql;
        try (InputStream in = new ClassPathResource("schema-auth.sql").getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement st = c.createStatement()) {
            for (String command : SqlSplitter.split(sql)) st.execute(command);
        }
    }

    private void bootstrapSuperAdmin(Connection c) throws SQLException {
        var auth = props.getAuth();
        String username = auth.getSuperadminDefaultUsername();
        if (username == null || username.isBlank()) return;
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO users (username, password_hash, role, must_change_password, created_by, password_changed_at)
                VALUES (?, ?, 'ADMIN', TRUE, 'system', ?)
                ON CONFLICT (username) DO NOTHING""")) {
            p.setString(1, username.trim());
            p.setString(2, passwords.hash(auth.getSuperadminDefaultPassword()));
            p.setTimestamp(3, Timestamp.from(nowSeconds()));
            if (p.executeUpdate() == 1) {
                log.warn("Created SuperAdmin account '{}'. Its password must be changed at first login.", username);
                if ("admin123".equals(auth.getSuperadminDefaultPassword()))
                    log.warn("SuperAdmin uses the default password. Set SUPERADMIN_PASSWORD in production.");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------------------------------------

    public LoginResponse authenticate(String username, String password) {
        ensureInitialized();
        Optional<User> found = find(username);
        if (found.isEmpty()) {
            passwords.verifyDummy(password);   // same cost as a wrong password: don't reveal which usernames exist
            throw new AuthException(401, "INVALID_CREDENTIALS", "Invalid username or password.");
        }
        User u = found.get();
        if (!passwords.verify(password, u.passwordHash()))
            throw new AuthException(401, "INVALID_CREDENTIALS", "Invalid username or password.");
        if (!u.active())
            throw new AuthException(403, "ACCOUNT_DISABLED", "This account has been deactivated. Contact your administrator.");

        exec("UPDATE users SET last_login = now() WHERE id = ?", u.id());
        log.info("AUDIT login user={} role={}", u.username(), u.role());
        return issue(u);
    }

    private LoginResponse issue(User u) {
        var t = tokens.generate(u.username(), u.role());
        return new LoginResponse(t.token(), u.username(), u.role(), u.mustChangePassword(), t.expiresAt());
    }

    /**
     * Loads the user behind a validated token and checks the account can still use it: it must exist, be active,
     * and the token must have been issued after the last password change (so a reset ends old sessions).
     */
    public AuthenticatedUser resolve(JwtTokenService.TokenClaims claims) {
        ensureInitialized();
        User u = find(claims.username())
                .orElseThrow(() -> new AuthException(401, "TOKEN_INVALID", "Unknown user. Please log in again."));
        if (!u.active())
            throw new AuthException(401, "ACCOUNT_DISABLED", "This account has been deactivated.");
        if (u.passwordChangedAt() != null && claims.issuedAt().isBefore(u.passwordChangedAt()))
            throw new AuthException(401, "TOKEN_REVOKED", "Your password was changed. Please log in again.");
        // Role and must-change flag come from the database, not the token, so admin changes apply immediately.
        return new AuthenticatedUser(u.username(), u.role(), u.mustChangePassword(), claims.tokenId(), claims.expiresAt());
    }

    /**
     * Changes the caller's password. The old password is required, except while the account is in
     * "must change password" state (the user has just logged in with the temporary password).
     *
     * @return a new login (the password change invalidates every earlier token, including the current one)
     */
    public LoginResponse changePassword(AuthenticatedUser caller, ChangePasswordRequest req) {
        User u = find(caller.username()).orElseThrow(() -> AuthException.unauthorized("Unknown user."));
        if (!u.mustChangePassword()) {
            if (req.oldPassword() == null || req.oldPassword().isEmpty())
                throw new IllegalArgumentException("Current password is required.");
            if (!passwords.verify(req.oldPassword(), u.passwordHash()))
                throw new AuthException(400, "WRONG_PASSWORD", "Current password is incorrect.");
        }
        passwords.validateNewPassword(u.username(), req.newPassword());
        if (passwords.verify(req.newPassword(), u.passwordHash()))
            throw new IllegalArgumentException("New password must be different from the current password.");

        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("""
                UPDATE users SET password_hash = ?, must_change_password = FALSE,
                                 password_changed_at = ?, reset_requested_at = NULL
                 WHERE id = ?""")) {
            p.setString(1, passwords.hash(req.newPassword()));
            p.setTimestamp(2, Timestamp.from(nowSeconds()));
            p.setLong(3, u.id());
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not change password: " + e.getMessage(), e);
        }
        log.info("AUDIT password-changed user={}", u.username());
        return issue(find(u.username()).orElseThrow());
    }

    /**
     * "Forgot password?": flags the account so the SuperAdmin sees the request in the user list.
     * Silent for unknown or inactive usernames so the endpoint cannot be used to discover accounts.
     */
    public void requestPasswordReset(String username) {
        ensureInitialized();
        if (username == null || username.isBlank()) return;
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(
                "UPDATE users SET reset_requested_at = now() WHERE lower(username) = lower(?) AND is_active")) {
            p.setString(1, username.trim());
            if (p.executeUpdate() == 1) log.info("AUDIT password-reset-requested user={}", username.trim());
        } catch (SQLException e) {
            log.error("Could not record password reset request: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Administration (SuperAdmin only; enforced by the controller)
    // ------------------------------------------------------------------------------------------------

    public TemporaryPassword createUser(String createdBy, CreateUserRequest req) {
        String username = req.username() == null ? "" : req.username().trim();
        if (!USERNAME.matcher(username).matches())
            throw new IllegalArgumentException("Username must be 3-50 characters: letters, digits, '.', '_' or '-'.");
        Role role = Role.parse(req.role());
        if (find(username).isPresent())
            throw new AuthException(409, "USER_EXISTS", "User '" + username + "' already exists.");

        String temp = passwords.generateTemporaryPassword();
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("""
                INSERT INTO users (username, password_hash, role, must_change_password, created_by, password_changed_at)
                VALUES (?, ?, ?, TRUE, ?, ?)""")) {
            p.setString(1, username);
            p.setString(2, passwords.hash(temp));
            p.setString(3, role.name());
            p.setString(4, createdBy);
            p.setTimestamp(5, Timestamp.from(nowSeconds()));
            p.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState()))   // unique_violation: created concurrently
                throw new AuthException(409, "USER_EXISTS", "User '" + username + "' already exists.");
            throw new IllegalStateException("Could not create user: " + e.getMessage(), e);
        }
        log.info("AUDIT user-created user={} role={} by={}", username, role, createdBy);
        return new TemporaryPassword(username, role, temp);
    }

    public List<User> listUsers() {
        ensureInitialized();
        List<User> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + COLUMNS + " FROM users ORDER BY lower(username)")) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list users: " + e.getMessage(), e);
        }
        return out;
    }

    /** Deactivates an account. Refuses to deactivate yourself or the last active ADMIN. */
    public void deactivate(String adminUsername, String username) {
        User u = require(username);
        if (u.username().equalsIgnoreCase(adminUsername))
            throw new IllegalArgumentException("You cannot deactivate your own account.");
        if (!u.active()) return;
        if (u.role() == Role.ADMIN && countActiveAdmins() <= 1)
            throw new IllegalArgumentException("Cannot deactivate the last active ADMIN.");
        exec("UPDATE users SET is_active = FALSE WHERE id = ?", u.id());
        log.info("AUDIT user-deactivated user={} by={}", u.username(), adminUsername);
    }

    public void activate(String adminUsername, String username) {
        User u = require(username);
        if (u.active()) return;
        exec("UPDATE users SET is_active = TRUE WHERE id = ?", u.id());
        log.info("AUDIT user-activated user={} by={}", u.username(), adminUsername);
    }

    /** Generates a new temporary password; the user must change it at next login and existing sessions end. */
    public TemporaryPassword resetPassword(String adminUsername, String username) {
        User u = require(username);
        String temp = passwords.generateTemporaryPassword();
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("""
                UPDATE users SET password_hash = ?, must_change_password = TRUE,
                                 password_changed_at = ?, reset_requested_at = NULL
                 WHERE id = ?""")) {
            p.setString(1, passwords.hash(temp));
            p.setTimestamp(2, Timestamp.from(nowSeconds()));
            p.setLong(3, u.id());
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset password: " + e.getMessage(), e);
        }
        log.info("AUDIT password-reset user={} by={}", u.username(), adminUsername);
        return new TemporaryPassword(u.username(), u.role(), temp);
    }

    // ------------------------------------------------------------------------------------------------
    // Data access helpers
    // ------------------------------------------------------------------------------------------------

    /** Case-insensitive lookup. */
    public Optional<User> find(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM users WHERE lower(username) = lower(?)")) {
            p.setString(1, username.trim());
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read user: " + e.getMessage(), e);
        }
    }

    private User require(String username) {
        return find(username).orElseThrow(() -> new NoSuchElementException("User '" + username + "' not found."));
    }

    private int countActiveAdmins() {
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND is_active")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count admins: " + e.getMessage(), e);
        }
    }

    private void exec(String sql, long id) {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, id);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Database error: " + e.getMessage(), e);
        }
    }

    private static User map(ResultSet rs) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                Role.parse(rs.getString("role")),
                rs.getBoolean("must_change_password"),
                rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("last_login")),
                rs.getBoolean("is_active"),
                instant(rs.getTimestamp("password_changed_at")),
                instant(rs.getTimestamp("reset_requested_at")));
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }

    /** JWT "issued at" has second precision, so password-change times are stored the same way. */
    private static Instant nowSeconds() { return Instant.now().truncatedTo(ChronoUnit.SECONDS); }
}
