package io.github.gattgeng.jcompose.api;

import java.util.Map;
import java.util.Objects;

/**
 * Options describing the desired replica counts per service.
 * @param services Desired replica count per service name. These counts are applied directly and take precedence over <code>deploy.replicas</code> declared in the Compose file.
 * @param timeoutSeconds Grace period in seconds when stopping surplus containers.
 * @param noDeps Do not also rescale the selection's dependency services.
 */
public record ComposeScaleOptions(
        Map<String, Integer> services,
        Integer timeoutSeconds,
        boolean noDeps) {
    public ComposeScaleOptions {
        Objects.requireNonNull(services, "services");
        services = services == null ? null : Map.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private Map<String, Integer> services;
        private Integer timeoutSeconds;
        private boolean noDeps;

        /**
         * Desired replica count per service name. These counts are applied directly and take precedence over <code>deploy.replicas</code> declared in the Compose file.
         * @param value Desired replica count per service name. These counts are applied directly and take precedence over <code>deploy.replicas</code> declared in the Compose file.
         * @return this builder
         */
        public Builder services(Map<String, Integer> value) { this.services = value; return this; }

        /**
         * Grace period in seconds when stopping surplus containers.
         * @param value Grace period in seconds when stopping surplus containers.
         * @return this builder
         */
        public Builder timeoutSeconds(Integer value) { this.timeoutSeconds = value; return this; }

        /**
         * Do not also rescale the selection's dependency services.
         * @param value Do not also rescale the selection's dependency services.
         * @return this builder
         */
        public Builder noDeps(boolean value) { this.noDeps = value; return this; }

        /** @return the immutable operation options */
        public ComposeScaleOptions build() { return new ComposeScaleOptions(services, timeoutSeconds, noDeps); }
    }
}