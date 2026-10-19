package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Options controlling a full <code>up</code> of the project.
 * @param detach Return once containers are started instead of following their output.
 * @param build Build images before starting containers, even if an image already exists.
 * @param forceRecreate Recreate containers even when their configuration did not change.
 * @param noRecreate Keep existing containers instead of recreating them.
 * @param noStart Create containers but do not start them.
 * @param pull Image pull policy: <code>policy</code> (honor each service's pull_policy), <code>always</code>, <code>missing</code>, or <code>never</code>.
 * @param removeOrphans Remove containers of services that no longer exist in the Compose file.
 * @param scale Per-service replica counts; these take precedence over <code>deploy.replicas</code> in the Compose file.
 * @param services Restricts the up to these service names (plus their dependencies unless {@code noDeps} is true); null targets every service.
 * @param timeoutSeconds Grace period in seconds when stopping containers during recreation.
 * @param waitForCompletion Block until the started containers are running (or healthy) before returning.
 * @param dependencyWaitTimeoutSeconds Maximum seconds to wait for each dependency to become ready; null waits until readiness, failure, or cancellation.
 * @param noBuild Never build images, failing when a required image is missing.
 * @param quietPull Suppress pull progress output.
 * @param renewAnonVolumes Recreate anonymous volumes instead of reusing them across recreations.
 * @param alwaysRecreateDeps Always recreate dependency services, even when their configuration did not change.
 * @param noDeps Do not start services the selected services depend on.
 * @param logConsumer Receives container output and status lines during the up; null discards them.
 */
public record ComposeUpOptions(
        boolean detach,
        boolean build,
        boolean forceRecreate,
        boolean noRecreate,
        boolean noStart,
        String pull,
        boolean removeOrphans,
        Map<String, Integer> scale,
        List<String> services,
        Integer timeoutSeconds,
        boolean waitForCompletion,
        Integer dependencyWaitTimeoutSeconds,
        boolean noBuild,
        boolean quietPull,
        boolean renewAnonVolumes,
        boolean alwaysRecreateDeps,
        boolean noDeps,
        ILogConsumer logConsumer) {
    public ComposeUpOptions {
        scale = scale == null ? null : Map.copyOf(scale);
        services = services == null ? null : List.copyOf(services);
    }

    /** Preserves the constructor signature from before the noDeps option was added. */
    public ComposeUpOptions(boolean detach, boolean build, boolean forceRecreate, boolean noRecreate, boolean noStart,
                            String pull, boolean removeOrphans, Map<String, Integer> scale, List<String> services,
                            Integer timeoutSeconds, boolean waitForCompletion, Integer dependencyWaitTimeoutSeconds,
                            boolean noBuild, boolean quietPull, boolean renewAnonVolumes, boolean alwaysRecreateDeps,
                            ILogConsumer logConsumer) {
        this(detach, build, forceRecreate, noRecreate, noStart, pull, removeOrphans, scale, services, timeoutSeconds,
                waitForCompletion, dependencyWaitTimeoutSeconds, noBuild, quietPull, renewAnonVolumes,
                alwaysRecreateDeps, false, logConsumer);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private boolean detach = true;
        private boolean build;
        private boolean forceRecreate;
        private boolean noRecreate;
        private boolean noStart;
        private String pull = "policy";
        private boolean removeOrphans;
        private Map<String, Integer> scale;
        private List<String> services;
        private Integer timeoutSeconds;
        private boolean waitForCompletion;
        private Integer dependencyWaitTimeoutSeconds;
        private boolean noBuild;
        private boolean quietPull;
        private boolean renewAnonVolumes;
        private boolean alwaysRecreateDeps;
        private boolean noDeps;
        private ILogConsumer logConsumer;

        /**
         * Return once containers are started instead of following their output.
         * @param value Return once containers are started instead of following their output.
         * @return this builder
         */
        public Builder detach(boolean value) { this.detach = value; return this; }

        /**
         * Build images before starting containers, even if an image already exists.
         * @param value Build images before starting containers, even if an image already exists.
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
         * Create containers but do not start them.
         * @param value Create containers but do not start them.
         * @return this builder
         */
        public Builder noStart(boolean value) { this.noStart = value; return this; }

        /**
         * Image pull policy: <code>policy</code> (honor each service's pull_policy), <code>always</code>, <code>missing</code>, or <code>never</code>.
         * @param value Image pull policy: <code>policy</code> (honor each service's pull_policy), <code>always</code>, <code>missing</code>, or <code>never</code>.
         * @return this builder
         */
        public Builder pull(String value) { this.pull = value; return this; }

        /**
         * Remove containers of services that no longer exist in the Compose file.
         * @param value Remove containers of services that no longer exist in the Compose file.
         * @return this builder
         */
        public Builder removeOrphans(boolean value) { this.removeOrphans = value; return this; }

        /**
         * Per-service replica counts; these take precedence over <code>deploy.replicas</code> in the Compose file.
         * @param value Per-service replica counts; these take precedence over <code>deploy.replicas</code> in the Compose file.
         * @return this builder
         */
        public Builder scale(Map<String, Integer> value) { this.scale = value; return this; }

        /**
         * Restricts the up to these service names (plus their dependencies unless {@code noDeps} is true); null targets every service.
         * @param value Restricts the up to these service names (plus their dependencies unless {@code noDeps} is true); null targets every service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Grace period in seconds when stopping containers during recreation.
         * @param value Grace period in seconds when stopping containers during recreation.
         * @return this builder
         */
        public Builder timeoutSeconds(Integer value) { this.timeoutSeconds = value; return this; }

        /**
         * Block until the started containers are running (or healthy) before returning.
         * @param value Block until the started containers are running (or healthy) before returning.
         * @return this builder
         */
        public Builder waitForCompletion(boolean value) { this.waitForCompletion = value; return this; }

        /**
         * Maximum seconds to wait for each dependency to become ready; null waits until readiness, failure, or cancellation.
         * @param value Maximum seconds to wait for each dependency to become ready; null waits until readiness, failure, or cancellation.
         * @return this builder
         */
        public Builder dependencyWaitTimeoutSeconds(Integer value) { this.dependencyWaitTimeoutSeconds = value; return this; }

        /**
         * Never build images, failing when a required image is missing.
         * @param value Never build images, failing when a required image is missing.
         * @return this builder
         */
        public Builder noBuild(boolean value) { this.noBuild = value; return this; }

        /**
         * Suppress pull progress output.
         * @param value Suppress pull progress output.
         * @return this builder
         */
        public Builder quietPull(boolean value) { this.quietPull = value; return this; }

        /**
         * Recreate anonymous volumes instead of reusing them across recreations.
         * @param value Recreate anonymous volumes instead of reusing them across recreations.
         * @return this builder
         */
        public Builder renewAnonVolumes(boolean value) { this.renewAnonVolumes = value; return this; }

        /**
         * Always recreate dependency services, even when their configuration did not change.
         * @param value Always recreate dependency services, even when their configuration did not change.
         * @return this builder
         */
        public Builder alwaysRecreateDeps(boolean value) { this.alwaysRecreateDeps = value; return this; }

        /**
         * Do not start services the selected services depend on.
         * @param value Do not start services the selected services depend on.
         * @return this builder
         */
        public Builder noDeps(boolean value) { this.noDeps = value; return this; }

        /**
         * Receives container output and status lines during the up; null discards them.
         * @param value Receives container output and status lines during the up; null discards them.
         * @return this builder
         */
        public Builder logConsumer(ILogConsumer value) { this.logConsumer = value; return this; }

        /** @return the immutable operation options */
        public ComposeUpOptions build() { return new ComposeUpOptions(detach, build, forceRecreate, noRecreate, noStart, pull, removeOrphans, scale, services, timeoutSeconds, waitForCompletion, dependencyWaitTimeoutSeconds, noBuild, quietPull, renewAnonVolumes, alwaysRecreateDeps, noDeps, logConsumer); }
    }
}
