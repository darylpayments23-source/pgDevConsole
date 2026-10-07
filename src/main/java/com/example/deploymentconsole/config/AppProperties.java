package com.example.deploymentconsole.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Map<String, Database> databases;
    private Execution execution = new Execution();

    public Map<String, Database> getDatabases() { return databases; }
    public void setDatabases(Map<String, Database> databases) { this.databases = databases; }
    public Execution getExecution() { return execution; }
    public void setExecution(Execution execution) { this.execution = execution; }
    private Auth auth = new Auth();
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public static class Auth {
        private String jwtSecret = "change-me-in-production";
        private int jwtExpiryHours = 24;
        private String superadminDefaultUsername = "admin";
        private String superadminDefaultPassword = "admin123";
        private int bcryptStrength = 10;
        private int minPasswordLength = 8;
        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String v) { jwtSecret = v; }
        public int getJwtExpiryHours() { return jwtExpiryHours; }
        public void setJwtExpiryHours(int v) { jwtExpiryHours = v; }
        public String getSuperadminDefaultUsername() { return superadminDefaultUsername; }
        public void setSuperadminDefaultUsername(String v) { superadminDefaultUsername = v; }
        public String getSuperadminDefaultPassword() { return superadminDefaultPassword; }
        public void setSuperadminDefaultPassword(String v) { superadminDefaultPassword = v; }
        public int getBcryptStrength() { return bcryptStrength; }
        public void setBcryptStrength(int v) { bcryptStrength = v; }
        public int getMinPasswordLength() { return minPasswordLength; }
        public void setMinPasswordLength(int v) { minPasswordLength = v; }
    }

    public static class Database {
        private String name;
        private String url;
        private String username;
        private String password;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class Execution {
        private int maxConcurrentDeployments = 1;
        private int scriptTimeoutSeconds = 1800;
        private int lockTimeoutMinutes = 30;
        private String historyDb;
        private String historyUsername;
        private String historyPassword;
        private boolean requireConfirmationForModified = true;
        public int getMaxConcurrentDeployments() { return maxConcurrentDeployments; }
        public void setMaxConcurrentDeployments(int v) { maxConcurrentDeployments = v; }
        public int getScriptTimeoutSeconds() { return scriptTimeoutSeconds; }
        public void setScriptTimeoutSeconds(int v) { scriptTimeoutSeconds = v; }
        public int getLockTimeoutMinutes() { return lockTimeoutMinutes; }
        public void setLockTimeoutMinutes(int v) { lockTimeoutMinutes = v; }
        public String getHistoryDb() { return historyDb; }
        public void setHistoryDb(String v) { historyDb = v; }
        public String getHistoryUsername() { return historyUsername; }
        public void setHistoryUsername(String v) { historyUsername = v; }
        public String getHistoryPassword() { return historyPassword; }
        public void setHistoryPassword(String v) { historyPassword = v; }
        public boolean isRequireConfirmationForModified() { return requireConfirmationForModified; }
        public void setRequireConfirmationForModified(boolean v) { requireConfirmationForModified = v; }
    }
}
