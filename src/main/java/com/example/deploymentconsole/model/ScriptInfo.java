package com.example.deploymentconsole.model;

public record ScriptInfo(
        int order,
        String path,
        String filename,
        long sequence,
        ScriptStatus status,
        Long durationMs,
        String error,
        String checksum,        // SHA-256 (hex) of the script content, null until computed
        boolean modified        // true if checksum differs from the last successful execution in this environment
) {
    /** Convenience constructor for a script that has not been checksummed yet. */
    public ScriptInfo(int order, String path, String filename, long sequence,
                      ScriptStatus status, Long durationMs, String error) {
        this(order, path, filename, sequence, status, durationMs, error, null, false);
    }
}
