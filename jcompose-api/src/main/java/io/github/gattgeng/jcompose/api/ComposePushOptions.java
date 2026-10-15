package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for pushing built images to their registries.
 * @param services Restricts the push to these service names; null pushes every project service's image.
 * @param ignoreFailures Continue pushing remaining images when one push fails.
 * @param quiet Suppress push progress output.
 */
public record ComposePushOptions(
        List<String> services,
        boolean ignoreFailures,
        boolean quiet) {
    public ComposePushOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean ignoreFailures;
        private boolean quiet;

        /**
         * Restricts the push to these service names; null pushes every project service's image.
         * @param value Restricts the push to these service names; null pushes every project service's image.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Continue pushing remaining images when one push fails.
         * @param value Continue pushing remaining images when one push fails.
         * @return this builder
         */
        public Builder ignoreFailures(boolean value) { this.ignoreFailures = value; return this; }

        /**
         * Suppress push progress output.
         * @param value Suppress push progress output.
         * @return this builder
         */
        public Builder quiet(boolean value) { this.quiet = value; return this; }

        /** @return the immutable operation options */
        public ComposePushOptions build() { return new ComposePushOptions(services, ignoreFailures, quiet); }
    }
}