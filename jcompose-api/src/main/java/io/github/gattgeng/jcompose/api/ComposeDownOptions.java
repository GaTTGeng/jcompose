package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for taking a project down.
 * @param removeVolumes Also remove named volumes declared by the project.
 * @param removeOrphans Also remove containers of services that no longer exist in the Compose file.
 * @param removeImages Image removal policy: <code>all</code> removes every project image, <code>local</code> only untagged ones.
 * @param services Restricts the teardown to these service names; networks are cleaned up only when the whole project goes down.
 * @param timeoutSeconds Grace period in seconds when stopping containers before force-removal.
 */
public record ComposeDownOptions(
        boolean removeVolumes,
        boolean removeOrphans,
        String removeImages,
        List<String> services,
        Integer timeoutSeconds) {
    public ComposeDownOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private boolean removeVolumes;
        private boolean removeOrphans;
        private String removeImages;
        private List<String> services;
        private Integer timeoutSeconds;

        /**
         * Also remove named volumes declared by the project.
         * @param value Also remove named volumes declared by the project.
         * @return this builder
         */
        public Builder removeVolumes(boolean value) { this.removeVolumes = value; return this; }

        /**
         * Also remove containers of services that no longer exist in the Compose file.
         * @param value Also remove containers of services that no longer exist in the Compose file.
         * @return this builder
         */
        public Builder removeOrphans(boolean value) { this.removeOrphans = value; return this; }

        /**
         * Image removal policy: <code>all</code> removes every project image, <code>local</code> only untagged ones.
         * @param value Image removal policy: <code>all</code> removes every project image, <code>local</code> only untagged ones.
         * @return this builder
         */
        public Builder removeImages(String value) { this.removeImages = value; return this; }

        /**
         * Restricts the teardown to these service names; networks are cleaned up only when the whole project goes down.
         * @param value Restricts the teardown to these service names; networks are cleaned up only when the whole project goes down.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Grace period in seconds when stopping containers before force-removal.
         * @param value Grace period in seconds when stopping containers before force-removal.
         * @return this builder
         */
        public Builder timeoutSeconds(Integer value) { this.timeoutSeconds = value; return this; }

        /** @return the immutable operation options */
        public ComposeDownOptions build() { return new ComposeDownOptions(removeVolumes, removeOrphans, removeImages, services, timeoutSeconds); }
    }
}