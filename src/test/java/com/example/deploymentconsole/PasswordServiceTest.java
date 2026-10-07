package com.example.deploymentconsole;

import com.example.deploymentconsole.config.AppProperties;
import com.example.deploymentconsole.service.PasswordService;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PasswordServiceTest {
    private final PasswordService service = new PasswordService(new AppProperties());

    @Test
    void hashesWithBcryptAndVerifies() {
        String hash = service.hash("s3cret-pass");
        assertTrue(hash.startsWith("$2a$10$"), "bcrypt with cost 10");
        assertTrue(service.verify("s3cret-pass", hash));
        assertFalse(service.verify("wrong", hash));
        assertNotEquals(hash, service.hash("s3cret-pass"));   // salted
    }

    @Test
    void verifyIsFalseForMissingOrMalformedHash() {
        assertFalse(service.verify("x", null));
        assertFalse(service.verify("x", "not-a-bcrypt-hash"));
        assertFalse(service.verify(null, service.hash("x")));
    }

    @Test
    void temporaryPasswordsAreStrongAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String p = service.generateTemporaryPassword();
            assertEquals(12, p.length());
            assertTrue(p.chars().anyMatch(Character::isUpperCase), p);
            assertTrue(p.chars().anyMatch(Character::isLowerCase), p);
            assertTrue(p.chars().anyMatch(Character::isDigit), p);
            assertDoesNotThrow(() -> service.validateNewPassword("someone", p));
            assertTrue(seen.add(p), "duplicate temporary password");
        }
    }

    @Test
    void passwordPolicy() {
        assertThrows(IllegalArgumentException.class, () -> service.validateNewPassword("bob", "short"));
        assertThrows(IllegalArgumentException.class, () -> service.validateNewPassword("bob12345", "BOB12345"));
        assertThrows(IllegalArgumentException.class, () -> service.validateNewPassword("bob", "x".repeat(73)));
        assertDoesNotThrow(() -> service.validateNewPassword("bob", "correct horse"));
    }
}
