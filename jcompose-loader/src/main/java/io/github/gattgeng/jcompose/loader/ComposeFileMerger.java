package io.github.gattgeng.jcompose.loader;

import io.github.gattgeng.jcompose.api.ComposeProject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Convenience facade for loading and merging multiple Compose files. */
public final class ComposeFileMerger {
    private final ComposeFileLoader loader;

    public ComposeFileMerger() {
        this(new ComposeFileLoader());
    }

    public ComposeFileMerger(ComposeFileLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** Loads and merges the supplied files in order, using the first as the base file. */
    public ComposeProject merge(Path workingDirectory, List<String> composeFiles) {
        return loader.loadMerged(workingDirectory, composeFiles);
    }

    /**
     * Loads and merges files in order with per-call interpolation values taking precedence over
     * process environment values and adjacent {@code .env} files.
     */
    public ComposeProject mergeWithEnv(Path workingDirectory, List<String> composeFiles,
                                        Map<String, String> environmentOverrides) {
        return loader.loadMerged(workingDirectory, composeFiles, environmentOverrides);
    }
}
