package io.github.gattgeng.jcompose.api;

import java.nio.file.Path;
import java.util.List;

/** Identifies a Compose project and the local settings used to load and manage it.
 * @param offline Prevents jcompose from initiating registry pulls or pushes; Docker builds may still fetch missing base images.
 * @param compatibility Enables legacy Compose compatibility behavior when supported by an operation.
 * @param validationMode Reports incomplete field support or rejects the project before Docker operations.
 */
public record ComposeProjectContext(
        String projectName,
        Path workingDirectory,
        String composeFileName,
        List<String> profiles,
        String socketPath,
        DockerRegistryAuth registryAuth,
        boolean offline,
        boolean compatibility,
        List<String> composeFiles,
        ComposeValidationMode validationMode) {
    public ComposeProjectContext {
        if (projectName == null || projectName.isBlank()) throw new IllegalArgumentException("projectName is required");
        if (workingDirectory == null) throw new IllegalArgumentException("workingDirectory is required");
        composeFileName = composeFileName == null || composeFileName.isBlank() ? "compose.yaml" : composeFileName;
        profiles = profiles == null ? null : List.copyOf(profiles);
        composeFiles = composeFiles == null || composeFiles.isEmpty() ? List.of(composeFileName) : List.copyOf(composeFiles);
        validationMode = validationMode == null ? ComposeValidationMode.REPORT : validationMode;
        if (composeFiles.stream().anyMatch(file -> file == null || file.isBlank())) {
            throw new IllegalArgumentException("composeFiles must contain non-blank file names");
        }
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    public ComposeProjectContext(String projectName, Path workingDirectory, String composeFileName,
                                 List<String> profiles, String socketPath, DockerRegistryAuth registryAuth,
                                 boolean offline, boolean compatibility) {
        this(projectName, workingDirectory, composeFileName, profiles, socketPath, registryAuth,
                offline, compatibility, null, ComposeValidationMode.REPORT);
    }

    public ComposeProjectContext(String projectName, Path workingDirectory, String composeFileName,
                                 List<String> profiles, String socketPath, DockerRegistryAuth registryAuth,
                                 boolean offline, boolean compatibility, List<String> composeFiles) {
        this(projectName, workingDirectory, composeFileName, profiles, socketPath, registryAuth,
                offline, compatibility, composeFiles, ComposeValidationMode.REPORT);
    }

    public ComposeProjectContext(String projectName, Path workingDirectory) {
        this(projectName, workingDirectory, "compose.yaml", null, null, null, false, false, null, ComposeValidationMode.REPORT);
    }
}
