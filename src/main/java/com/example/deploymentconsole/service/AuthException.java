package com.example.deploymentconsole.service;

/** An authentication/authorization failure carrying the HTTP status and a machine-readable error code. */
public class AuthException extends RuntimeException {
    private final int status;
    private final String code;

    public AuthException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() { return status; }
    public String getCode() { return code; }

    public static AuthException unauthorized(String message) { return new AuthException(401, "UNAUTHORIZED", message); }
    public static AuthException forbidden(String message) { return new AuthException(403, "FORBIDDEN", message); }
}
