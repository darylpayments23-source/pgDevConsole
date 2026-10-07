package com.example.deploymentconsole;

import com.example.deploymentconsole.config.AppProperties;
import com.example.deploymentconsole.model.Role;
import com.example.deploymentconsole.service.AuthException;
import com.example.deploymentconsole.service.JwtTokenService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTest {

    private static JwtTokenService service(String secret) {
        AppProperties props = new AppProperties();
        props.getAuth().setJwtSecret(secret);
        props.getAuth().setJwtExpiryHours(24);
        return new JwtTokenService(props);
    }

    @Test
    void issuesAndValidatesToken() {
        JwtTokenService jwt = service("test-secret");
        var issued = jwt.generate("alice", Role.DEPLOYER);
        var claims = jwt.validate(issued.token());
        assertEquals("alice", claims.username());
        assertEquals(Role.DEPLOYER, claims.role());
        assertEquals(issued.tokenId(), claims.tokenId());
        long hours = Duration.between(Instant.now(), claims.expiresAt()).toHours();
        assertTrue(hours >= 23 && hours <= 24, "expires in ~24h");
    }

    @Test
    void rejectsTamperedOrForeignTokens() {
        JwtTokenService jwt = service("test-secret");
        String token = jwt.generate("alice", Role.VIEWER).token();
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];
        assertEquals(401, assertThrows(AuthException.class, () -> jwt.validate(tampered)).getStatus());
        String foreign = service("another-secret").generate("alice", Role.ADMIN).token();
        assertThrows(AuthException.class, () -> jwt.validate(foreign));
        assertThrows(AuthException.class, () -> jwt.validate("garbage"));
        assertThrows(AuthException.class, () -> jwt.validate(null));
    }

    @Test
    void revokedTokenIsRejected() {
        JwtTokenService jwt = service("test-secret");
        var issued = jwt.generate("bob", Role.ADMIN);
        jwt.validate(issued.token());
        jwt.revoke(issued.tokenId(), issued.expiresAt());
        AuthException e = assertThrows(AuthException.class, () -> jwt.validate(issued.token()));
        assertEquals("TOKEN_REVOKED", e.getCode());
    }
}
