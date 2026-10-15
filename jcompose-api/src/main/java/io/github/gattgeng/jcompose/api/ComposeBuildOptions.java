package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Options controlling image builds for services with a <code>build</code> section.
 * @param services Restricts the build to these service names; null builds every service that defines a build section.
 * @param noCache Disables the layer cache so every instruction runs again.
 * @param pull Always pull base images before building.
 * @param quiet Suppresses build progress output.
 * @param buildArgs Build arguments passed to the Dockerfile, overriding those declared in the Compose file.
 * @param labels Extra labels applied to the built image.
 * @param platform Target platform for the build, for example <code>linux/amd64</code>.
 * @param progress Progress output format requested from the builder.
 * @param target Multi-stage build target to stop at.
 * @param memory Memory limit for the build, as a size string understood by the builder.
 * @param builderName Named builder instance to use instead of the default.
 * @param logConsumer Receives build progress lines and completion status; null discards progress output.
 */
public record ComposeBuildOptions(
        List<String> services,
        boolean noCache,
        boolean pull,
        boolean quiet,
        Map<String, String> buildArgs,
        Map<String, String> labels,
        String platform,
        String progress,
        String target,
        String memory,
        String builderName,
        ILogConsumer logConsumer) {
    public ComposeBuildOptions {
        services = services == null ? null : List.copyOf(services);
        buildArgs = buildArgs == null ? null : Map.copyOf(buildArgs);
        labels = labels == null ? null : Map.copyOf(labels);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean noCache;
        private boolean pull;
        private boolean quiet;
        private Map<String, String> buildArgs;
        private Map<String, String> labels;
        private String platform;
        private String progress;
        private String target;
        private String memory;
        private String builderName;
        private ILogConsumer logConsumer;

        /**
         * Restricts the build to these service names; null builds every service that defines a build section.
         * @param value Restricts the build to these service names; null builds every service that defines a build section.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Disables the layer cache so every instruction runs again.
         * @param value Disables the layer cache so every instruction runs again.
         * @return this builder
         */
        public Builder noCache(boolean value) { this.noCache = value; return this; }

        /**
         * Always pull base images before building.
         * @param value Always pull base images before building.
         * @return this builder
         */
        public Builder pull(boolean value) { this.pull = value; return this; }

        /**
         * Suppresses build progress output.
         * @param value Suppresses build progress output.
         * @return this builder
         */
        public Builder quiet(boolean value) { this.quiet = value; return this; }

        /**
         * Build arguments passed to the Dockerfile, overriding those declared in the Compose file.
         * @param value Build arguments passed to the Dockerfile, overriding those declared in the Compose file.
         * @return this builder
         */
        public Builder buildArgs(Map<String, String> value) { this.buildArgs = value; return this; }

        /**
         * Extra labels applied to the built image.
         * @param value Extra labels applied to the built image.
         * @return this builder
         */
        public Builder labels(Map<String, String> value) { this.labels = value; return this; }

        /**
         * Target platform for the build, for example <code>linux/amd64</code>.
         * @param value Target platform for the build, for example <code>linux/amd64</code>.
         * @return this builder
         */
        public Builder platform(String value) { this.platform = value; return this; }

        /**
         * Progress output format requested from the builder.
         * @param value Progress output format requested from the builder.
         * @return this builder
         */
        public Builder progress(String value) { this.progress = value; return this; }

        /**
         * Multi-stage build target to stop at.
         * @param value Multi-stage build target to stop at.
         * @return this builder
         */
        public Builder target(String value) { this.target = value; return this; }

        /**
         * Memory limit for the build, as a size string understood by the builder.
         * @param value Memory limit for the build, as a size string understood by the builder.
         * @return this builder
         */
        public Builder memory(String value) { this.memory = value; return this; }

        /**
         * Named builder instance to use instead of the default.
         * @param value Named builder instance to use instead of the default.
         * @return this builder
         */
        public Builder builderName(String value) { this.builderName = value; return this; }

        /**
         * Receives build progress lines and completion status; null discards progress output.
         * @param value Receives build progress lines and completion status; null discards progress output.
         * @return this builder
         */
        public Builder logConsumer(ILogConsumer value) { this.logConsumer = value; return this; }

        /** @return the immutable operation options */
        public ComposeBuildOptions build() { return new ComposeBuildOptions(services, noCache, pull, quiet, buildArgs, labels, platform, progress, target, memory, builderName, logConsumer); }
    }
}
