package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for rendering the loaded configuration within one Compose project.
 * @param projectName Project name written to the rendered configuration; defaults to the context's project name.
 * @param services Service names to include in the rendered configuration; null includes services active under the context's profiles.
 * @param containers Legacy container names or IDs; generation cannot inspect containers and rejects nonempty values.
 */
public record ComposeGenerateOptions(
        String projectName,
        List<String> services,
        List<String> containers) {
    public ComposeGenerateOptions {
        services = services == null ? null : List.copyOf(services);
        containers = containers == null ? null : List.copyOf(containers);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private String projectName;
        private List<String> services;
        private List<String> containers;

        /**
         * Project name written to the rendered configuration; defaults to the context's project name.
         * @param value Project name written to the rendered configuration; defaults to the context's project name.
         * @return this builder
         */
        public Builder projectName(String value) { this.projectName = value; return this; }

        /**
         * Service names to include in the rendered configuration; null includes services active under the context's profiles.
         * @param value Service names to include in the rendered configuration; null includes services active under the context's profiles.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Legacy container names or IDs; generation cannot inspect containers and rejects nonempty values.
         * @param value Legacy container names or IDs; generation cannot inspect containers and rejects nonempty values.
         * @return this builder
         */
        public Builder containers(List<String> value) { this.containers = value; return this; }

        /** @return the immutable operation options */
        public ComposeGenerateOptions build() { return new ComposeGenerateOptions(projectName, services, containers); }
    }
}