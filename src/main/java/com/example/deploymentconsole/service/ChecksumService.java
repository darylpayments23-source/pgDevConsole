package com.example.deploymentconsole.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes SHA-256 checksums of SQL scripts. */
@Service
public class ChecksumService {
    private static final String ALGORITHM = "SHA-256";

    /** SHA-256 of the file content as 64 lowercase hex characters (streamed, so large scripts are fine). */
    public String computeChecksum(Path filePath) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(filePath)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** SHA-256 of bytes already in memory (lets the executor hash exactly what it is about to run). */
    public String computeChecksum(byte[] content) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance(ALGORITHM); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(ALGORITHM + " not available", e); }
    }
}
