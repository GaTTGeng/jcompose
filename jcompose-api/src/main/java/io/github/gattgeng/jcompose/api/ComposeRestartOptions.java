package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for restarting the project's containers.
 * @param services Restricts the restart to these service names; null restarts every project service.
 * @param timeoutSeconds Optional grace period in seconds before containers are force-killed on shutdown; null uses each container's configured stop timeout.
 * @param noDeps Do not restart dependent services along with the selection.
 */
public record ComposeRestartOptions(
        List<String> services,
        Integer timeoutSeconds,
        boolean noDeps) {
    public ComposeRestartOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private Integer timeoutSeconds;
        private boolean noDeps;

        /**
         * Restricts the restart to these service names; null restarts every project service.
         * @param value Restricts the restart to these service names; null restarts every project service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Optional grace period in seconds before containers are force-killed on shutdown; null uses each container's configured stop timeout.
         * @param value Grace period in seconds before containers are force-killed on shutdown; null uses each container's configured stop timeout.
         * @return this builder
         */
        public Builder timeoutSeconds(Integer value) { this.timeoutSeconds = value; return this; }

        /**
         * Do not restart dependent services along with the selection.
         * @param value Do not restart dependent services along with the selection.
         * @return this builder
         */
        public Builder noDeps(boolean value) { this.noDeps = value; return this; }

        /** @return the immutable operation options */
        public ComposeRestartOptions build() { return new ComposeRestartOptions(services, timeoutSeconds, noDeps); }
    }
}
