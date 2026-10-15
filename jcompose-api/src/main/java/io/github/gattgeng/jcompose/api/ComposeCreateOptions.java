package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Options for creating service containers without a full <code>up</code>.
 * @param services Restricts creation to these service names; null creates every selected service.
 * @param build Build images before creating containers.
 * @param forceRecreate Recreate containers even when their configuration did not change.
 * @param noRecreate Keep existing containers instead of recreating them.
 * @param pull Image pull policy: <code>policy</code> (honor each service's pull_policy), <code>always</code>, <code>missing</code>, or <code>never</code>.
 * @param scale Per-service replica counts; these take precedence over <code>deploy.replicas</code> in the Compose file.
 * @param removeOrphans Remove containers of services that no longer exist in the Compose file.
 * @param quietPull Suppress pull progress output.
 * @param timeoutSeconds Grace period in seconds when stopping containers during recreation.
 * @param dependencyWaitTimeoutSeconds Maximum seconds to wait for each dependency to become ready; null waits until readiness, failure, or cancellation.
 */
public record ComposeCreateOptions(
        List<String> services,
        boolean build,
        boolean forceRecreate,
        boolean noRecreate,
        String pull,
        Map<String, Integer> scale,
        boolean removeOrphans,
        boolean quietPull,
        Integer timeoutSeconds,
        Integer dependencyWaitTimeoutSeconds) {
    public ComposeCreateOptions {
        services = services == null ? null : List.copyOf(services);
        scale = scale == null ? null : Map.copyOf(scale);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean build;
        private boolean forceRecreate;
        private boolean noRecreate;
        private String pull = "policy";
        private Map<String, Integer> scale;
        private boolean removeOrphans;
        private boolean quietPull;
        private Integer timeoutSeconds;
        private Integer dependencyWaitTimeoutSeconds;

        /**
         * Restricts creation to these service names; null creates every selected service.
         * @param value Restricts creation to these service names; null creates every selected service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Build images before creating containers.
         * @param value Build images before creating containers.
         * @return this builder
         */
        public Builder build(boolean value) { this.build = value; return this; }

        /**
         * Recreate containers even when their configuration did not change.
         * @param value Recreate containers even when their configuration did not change.
         * @return this builder
         */
        public Builder forceRecreate(boolean value) { this.forceRecreate = value; return this; }

        /**
         * Keep existing containers instead of recreating them.
         * @param value Keep existing containers instead of recreating them.
         * @return this builder
         */
        public Builder noRecreate(boolean value) { this.noRecreate = value; return this; }

        /**
         * Image pull policy: <code>policy</code> (honor each service's pull_policy), <code>always</code>, <code>missing</code>, or <code>never</code>.
         * @param value Image pull policy: <code>policy</code> (honor each service's pull_policy), <code>always</code>, <code>missing</code>, or <code>never</code>.
         * @return this builder
         */
        public Builder pull(String value) { this.pull = value; return this; }

        /**
         * Per-service replica counts; these take precedence over <code>deploy.replicas</code> in the Compose file.
         * @param value Per-service replica counts; these take precedence over <code>deploy.replicas</code> in the Compose file.
         * @return this builder
         */
        public Builder scale(Map<String, Integer> value) { this.scale = value; return this; }

        /**
         * Remove containers of services that no longer exist in the Compose file.
         * @param value Remove containers of services that no longer exist in the Compose file.
         * @return this builder
         */
        public Builder removeOrphans(boolean value) { this.removeOrphans = value; return this; }

        /**
         * Suppress pull progress output.
         * @param value Suppress pull progress output.
         * @return this builder
         */
        public Builder quietPull(boolean value) { this.quietPull = value; return this; }

        /**
         * Grace period in seconds when stopping containers during recreation.
         * @param value Grace period in seconds when stopping containers during recreation.
         * @return this builder
         */
        public Builder timeoutSeconds(Integer value) { this.timeoutSeconds = value; return this; }

        /**
         * Maximum seconds to wait for each dependency to become ready; null waits until readiness, failure, or cancellation.
         * @param value Maximum seconds to wait for each dependency to become ready; null waits until readiness, failure, or cancellation.
         * @return this builder
         */
        public Builder dependencyWaitTimeoutSeconds(Integer value) { this.dependencyWaitTimeoutSeconds = value; return this; }

        /** @return the immutable operation options */
        public ComposeCreateOptions build() { return new ComposeCreateOptions(services, build, forceRecreate, noRecreate, pull, scale, removeOrphans, quietPull, timeoutSeconds, dependencyWaitTimeoutSeconds); }
    }
}