package io.github.gattgeng.jcompose.api;

import java.nio.file.Path;
import java.util.List;

/** Identifies a Compose project and the local settings used to load and manage it. */
public record ComposeProjectContext(
        String projectName,
        Path workingDirectory,
        String composeFileName,
        List<String> profiles,
        String socketPath,
        DockerRegistryAuth registryAuth,
        boolean offline,
        boolean compatibility) {
    public ComposeProjectContext {
        if (projectName == null || projectName.isBlank()) throw new IllegalArgumentException("projectName is required");
        if (workingDirectory == null) throw new IllegalArgumentException("workingDirectory is required");
        composeFileName = composeFileName == null || composeFileName.isBlank() ? "docker-compose.yml" : composeFileName;
        profiles = profiles == null ? null : List.copyOf(profiles);
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    public ComposeProjectContext(String projectName, Path workingDirectory) {
        this(projectName, workingDirectory, "docker-compose.yml", null, null, null, false, false);
    }
}
