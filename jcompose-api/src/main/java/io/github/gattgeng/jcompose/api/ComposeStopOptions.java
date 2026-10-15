package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for stopping the project's containers.
 * @param services Restricts the stop to these service names; null stops every project service.
 * @param timeoutSeconds Optional grace period in seconds before containers are force-killed; null uses each container's configured stop timeout.
 */
public record ComposeStopOptions(
        List<String> services,
        Integer timeoutSeconds) {
    public ComposeStopOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private Integer timeoutSeconds;

        /**
         * Restricts the stop to these service names; null stops every project service.
         * @param value Restricts the stop to these service names; null stops every project service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Optional grace period in seconds before containers are force-killed; null uses each container's configured stop timeout.
         * @param value Grace period in seconds before containers are force-killed; null uses each container's configured stop timeout.
         * @return this builder
         */
        public Builder timeoutSeconds(Integer value) { this.timeoutSeconds = value; return this; }

        /** @return the immutable operation options */
        public ComposeStopOptions build() { return new ComposeStopOptions(services, timeoutSeconds); }
    }
}
