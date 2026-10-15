package io.github.gattgeng.jcompose.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Default values used when application code creates a project context. */
@ConfigurationProperties(prefix = "jcompose")
public class JComposeProperties {
    /** Default Docker Engine socket/endpoint; null lets docker-java use its normal discovery. */
    private String defaultSocketPath;
    /** Default project name; null means callers must provide one. */
    private String defaultProjectName;

    public String getDefaultSocketPath() { return defaultSocketPath; }
    public void setDefaultSocketPath(String defaultSocketPath) { this.defaultSocketPath = defaultSocketPath; }
    public String getDefaultProjectName() { return defaultProjectName; }
    public void setDefaultProjectName(String defaultProjectName) { this.defaultProjectName = defaultProjectName; }
}
