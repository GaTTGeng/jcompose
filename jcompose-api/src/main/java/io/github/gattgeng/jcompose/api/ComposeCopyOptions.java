package io.github.gattgeng.jcompose.api;

import java.util.Objects;

/**
 * Options for copying files between the host and a service container.
 * @param source Copy source: a host path, or a <code>service:/container/path</code> reference.
 * @param destination Copy destination: a host path, or a <code>service:/container/path</code> reference.
 * @param followLink Follow symlinks in the source location instead of copying them as links.
 * @param copyUidGid Preserve the source UID/GID on copied files (requires sufficient privileges).
 * @param index Replica index within the service; null targets the first matching container.
 * @param all Apply the copy to every replica of the service instead of a single container.
 */
public record ComposeCopyOptions(
        String source,
        String destination,
        boolean followLink,
        boolean copyUidGid,
        Integer index,
        boolean all) {
    public ComposeCopyOptions {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private String source;
        private String destination;
        private boolean followLink;
        private boolean copyUidGid;
        private Integer index;
        private boolean all;

        /**
         * Copy source: a host path, or a <code>service:/container/path</code> reference.
         * @param value Copy source: a host path, or a <code>service:/container/path</code> reference.
         * @return this builder
         */
        public Builder source(String value) { this.source = value; return this; }

        /**
         * Copy destination: a host path, or a <code>service:/container/path</code> reference.
         * @param value Copy destination: a host path, or a <code>service:/container/path</code> reference.
         * @return this builder
         */
        public Builder destination(String value) { this.destination = value; return this; }

        /**
         * Follow symlinks in the source location instead of copying them as links.
         * @param value Follow symlinks in the source location instead of copying them as links.
         * @return this builder
         */
        public Builder followLink(boolean value) { this.followLink = value; return this; }

        /**
         * Preserve the source UID/GID on copied files (requires sufficient privileges).
         * @param value Preserve the source UID/GID on copied files (requires sufficient privileges).
         * @return this builder
         */
        public Builder copyUidGid(boolean value) { this.copyUidGid = value; return this; }

        /**
         * Replica index within the service; null targets the first matching container.
         * @param value Replica index within the service; null targets the first matching container.
         * @return this builder
         */
        public Builder index(Integer value) { this.index = value; return this; }

        /**
         * Apply the copy to every replica of the service instead of a single container.
         * @param value Apply the copy to every replica of the service instead of a single container.
         * @return this builder
         */
        public Builder all(boolean value) { this.all = value; return this; }

        /** @return the immutable operation options */
        public ComposeCopyOptions build() { return new ComposeCopyOptions(source, destination, followLink, copyUidGid, index, all); }
    }
}