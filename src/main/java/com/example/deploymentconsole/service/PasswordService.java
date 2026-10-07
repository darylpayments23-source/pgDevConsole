package com.example.deploymentconsole.service;

import com.example.deploymentconsole.config.AppProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/** BCrypt hashing/verification, temporary password generation and the password policy. */
@Service
public class PasswordService {
    // No 0/O, 1/l/I: temporary passwords are read out or copied by hand.
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = UPPER + LOWER + DIGITS;
    private static final int TEMP_PASSWORD_LENGTH = 12;

    private final BCryptPasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();
    private final int minLength;
    /** A real hash, used to spend the same time on unknown usernames as on wrong passwords. */
    private final String dummyHash;

    public PasswordService(AppProperties props) {
        this.encoder = new BCryptPasswordEncoder(props.getAuth().getBcryptStrength(), random);
        this.minLength = Math.max(1, props.getAuth().getMinPasswordLength());
        this.dummyHash = encoder.encode("timing-equalizer");
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean verify(String rawPassword, String hash) {
        if (rawPassword == null || hash == null || hash.isBlank()) return false;
        try {
            return encoder.matches(rawPassword, hash);
        } catch (IllegalArgumentException e) {
            return false;   // malformed hash in the database
        }
    }

    /** Burns roughly one bcrypt verification worth of time (login with an unknown username). */
    public void verifyDummy(String rawPassword) {
        try {
            encoder.matches(rawPassword == null ? "" : rawPassword, dummyHash);
        } catch (IllegalArgumentException ignored) {
            // over-long input; nothing to equalize
        }
    }

    /** A random 12-character password with at least one upper-case letter, lower-case letter and digit. */
    public String generateTemporaryPassword() {
        char[] out = new char[TEMP_PASSWORD_LENGTH];
        out[0] = pick(UPPER);
        out[1] = pick(LOWER);
        out[2] = pick(DIGITS);
        for (int i = 3; i < out.length; i++) out[i] = pick(ALL);
        for (int i = out.length - 1; i > 0; i--) {   // shuffle so the guaranteed classes aren't always first
            int j = random.nextInt(i + 1);
            char t = out[i]; out[i] = out[j]; out[j] = t;
        }
        return new String(out);
    }

    /** Throws IllegalArgumentException if {@code newPassword} does not meet the policy. */
    public void validateNewPassword(String username, String newPassword) {
        if (newPassword == null || newPassword.length() < minLength)
            throw new IllegalArgumentException("Password must be at least " + minLength + " characters.");
        if (newPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)   // bcrypt limit
            throw new IllegalArgumentException("Password is too long (max 72 bytes).");
        if (newPassword.isBlank())
            throw new IllegalArgumentException("Password must not be blank.");
        if (username != null && newPassword.equalsIgnoreCase(username))
            throw new IllegalArgumentException("Password must not be the same as the username.");
    }

    public int getMinLength() { return minLength; }

    private char pick(String chars) { return chars.charAt(random.nextInt(chars.length())); }
}
