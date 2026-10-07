package com.example.deploymentconsole.service;

import com.example.deploymentconsole.config.AppProperties;
import com.example.deploymentconsole.model.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and validates HS256-signed JWTs.
 *
 * <p>Logout is supported through an in-memory deny-list of token ids ({@code jti}) kept until each token's
 * own expiry. With several application instances, a logged-out token stays valid on the other instances
 * until it expires; password changes and deactivation are enforced on every instance (see JwtAuthFilter).</p>
 */
@Service
public class JwtTokenService {
    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final String DEFAULT_SECRET = "change-me-in-production";
    private static final String ISSUER = "pg-deployment-console";

    /** The validated content of a token. */
    public record TokenClaims(String username, Role role, String tokenId, Instant issuedAt, Instant expiresAt) {}

    /** A freshly issued token. */
    public record IssuedToken(String token, String tokenId, Instant expiresAt) {}

    private final SecretKey key;
    private final Duration expiry;
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public JwtTokenService(AppProperties props) {
        String secret = props.getAuth().getJwtSecret();
        if (secret == null || secret.isBlank())
            throw new IllegalStateException("app.auth.jwt-secret must be set");
        if (DEFAULT_SECRET.equals(secret))
            log.warn("app.auth.jwt-secret is the default value. Set the JWT_SECRET environment variable in production.");
        this.key = deriveKey(secret);
        int hours = props.getAuth().getJwtExpiryHours();
        if (hours <= 0) throw new IllegalStateException("app.auth.jwt-expiry-hours must be positive");
        this.expiry = Duration.ofHours(hours);
    }

    /**
     * HS256 needs a 256-bit key; hashing the configured secret gives a full-length key from a secret of
     * any length (the secret itself should still be long and random in production).
     */
    private static SecretKey deriveKey(String secret) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(bytes, "HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public IssuedToken generate(String username, Role role) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);   // JWT times have second precision
        Instant exp = now.plus(expiry);
        String id = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(id)
                .issuer(ISSUER)
                .subject(username)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, id, exp);
    }

    /** Validates signature, issuer, expiry and revocation. Throws {@link AuthException} (401) if invalid. */
    public TokenClaims validate(String token) {
        if (token == null || token.isBlank()) throw AuthException.unauthorized("Missing token");
        Claims c;
        try {
            c = Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new AuthException(401, "TOKEN_EXPIRED", "Session expired. Please log in again.");
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthException(401, "TOKEN_INVALID", "Invalid token.");
        }
        if (c.getId() == null || c.getSubject() == null || c.getIssuedAt() == null || c.getExpiration() == null)
            throw new AuthException(401, "TOKEN_INVALID", "Invalid token.");
        if (revoked.containsKey(c.getId()))
            throw new AuthException(401, "TOKEN_REVOKED", "You have been logged out. Please log in again.");
        Role role;
        try {
            role = Role.parse(c.get("role", String.class));
        } catch (IllegalArgumentException e) {
            throw new AuthException(401, "TOKEN_INVALID", "Invalid token.");
        }
        return new TokenClaims(c.getSubject(), role, c.getId(), c.getIssuedAt().toInstant(), c.getExpiration().toInstant());
    }

    /** Revokes a token until its natural expiry (logout). */
    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId == null) return;
        Instant now = Instant.now();
        revoked.values().removeIf(exp -> exp.isBefore(now));   // housekeeping: forget tokens that expired anyway
        revoked.put(tokenId, expiresAt == null ? now.plus(expiry) : expiresAt);
    }
}
