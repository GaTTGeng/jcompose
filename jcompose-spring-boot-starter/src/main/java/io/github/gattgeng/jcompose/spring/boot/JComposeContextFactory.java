package io.github.gattgeng.jcompose.spring.boot;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;

import java.nio.file.Path;
import java.util.List;

/** Creates project contexts with application-level jcompose defaults. */
public final class JComposeContextFactory {
    private final JComposeProperties properties;

    JComposeContextFactory(JComposeProperties properties) {
        this.properties = properties;
    }

    /** Creates a context using the configured default project name and Docker endpoint. */
    public ComposeProjectContext create(Path workingDirectory) {
        return create(properties.getDefaultProjectName(), workingDirectory, "compose.yaml", null);
    }

    /** Creates a context with an explicit project name and the configured Docker endpoint. */
    public ComposeProjectContext create(String projectName, Path workingDirectory) {
        return create(projectName, workingDirectory, "compose.yaml", null);
    }

    /** Creates a context with per-project settings while retaining the configured Docker endpoint. */
    public ComposeProjectContext create(String projectName, Path workingDirectory, String composeFileName, List<String> profiles) {
        return new ComposeProjectContext(projectName, workingDirectory, composeFileName, profiles,
                properties.getDefaultSocketPath(), null, false, false);
    }
}
