package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for pulling images referenced by the project.
 * @param services Restricts the pull to these service names; null pulls every project service's image.
 * @param includeDeps Also pull images of services the selection depends on.
 * @param quiet Suppress pull progress output.
 * @param platform Target platform for the pulled image, for example <code>linux/amd64</code>.
 * @param ignoreFailures Continue pulling remaining images when one pull fails.
 */
public record ComposePullOptions(
        List<String> services,
        boolean includeDeps,
        boolean quiet,
        String platform,
        boolean ignoreFailures) {
    public ComposePullOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean includeDeps;
        private boolean quiet;
        private String platform;
        private boolean ignoreFailures;

        /**
         * Restricts the pull to these service names; null pulls every project service's image.
         * @param value Restricts the pull to these service names; null pulls every project service's image.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Also pull images of services the selection depends on.
         * @param value Also pull images of services the selection depends on.
         * @return this builder
         */
        public Builder includeDeps(boolean value) { this.includeDeps = value; return this; }

        /**
         * Suppress pull progress output.
         * @param value Suppress pull progress output.
         * @return this builder
         */
        public Builder quiet(boolean value) { this.quiet = value; return this; }

        /**
         * Target platform for the pulled image, for example <code>linux/amd64</code>.
         * @param value Target platform for the pulled image, for example <code>linux/amd64</code>.
         * @return this builder
         */
        public Builder platform(String value) { this.platform = value; return this; }

        /**
         * Continue pulling remaining images when one pull fails.
         * @param value Continue pulling remaining images when one pull fails.
         * @return this builder
         */
        public Builder ignoreFailures(boolean value) { this.ignoreFailures = value; return this; }

        /** @return the immutable operation options */
        public ComposePullOptions build() { return new ComposePullOptions(services, includeDeps, quiet, platform, ignoreFailures); }
    }
}