package io.github.gattgeng.jcompose.api;

import java.util.Map;
import java.util.Objects;

/**
 * Options for committing a service container to a new image.
 * @param service Service whose container is committed.
 * @param reference Image reference for the committed image; defaults to <code>project-service:latest</code>.
 * @param author Author recorded on the new image.
 * @param message Commit message recorded on the new image.
 * @param changes Additional image configuration changes (Dockerfile-style instructions) applied at commit time.
 * @param pause Pause the container while committing so the filesystem is consistent.
 * @param index Replica index within the service; null commits the first matching container.
 */
public record ComposeCommitOptions(
        String service,
        String reference,
        String author,
        String message,
        Map<String, String> changes,
        boolean pause,
        Integer index) {
    public ComposeCommitOptions {
        Objects.requireNonNull(service, "service");
        changes = changes == null ? null : Map.copyOf(changes);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private String service;
        private String reference;
        private String author;
        private String message;
        private Map<String, String> changes;
        private boolean pause = true;
        private Integer index;

        /**
         * Service whose container is committed.
         * @param value Service whose container is committed.
         * @return this builder
         */
        public Builder service(String value) { this.service = value; return this; }

        /**
         * Image reference for the committed image; defaults to <code>project-service:latest</code>.
         * @param value Image reference for the committed image; defaults to <code>project-service:latest</code>.
         * @return this builder
         */
        public Builder reference(String value) { this.reference = value; return this; }

        /**
         * Author recorded on the new image.
         * @param value Author recorded on the new image.
         * @return this builder
         */
        public Builder author(String value) { this.author = value; return this; }

        /**
         * Commit message recorded on the new image.
         * @param value Commit message recorded on the new image.
         * @return this builder
         */
        public Builder message(String value) { this.message = value; return this; }

        /**
         * Additional image configuration changes (Dockerfile-style instructions) applied at commit time.
         * @param value Additional image configuration changes (Dockerfile-style instructions) applied at commit time.
         * @return this builder
         */
        public Builder changes(Map<String, String> value) { this.changes = value; return this; }

        /**
         * Pause the container while committing so the filesystem is consistent.
         * @param value Pause the container while committing so the filesystem is consistent.
         * @return this builder
         */
        public Builder pause(boolean value) { this.pause = value; return this; }

        /**
         * Replica index within the service; null commits the first matching container.
         * @param value Replica index within the service; null commits the first matching container.
         * @return this builder
         */
        public Builder index(Integer value) { this.index = value; return this; }

        /** @return the immutable operation options */
        public ComposeCommitOptions build() { return new ComposeCommitOptions(service, reference, author, message, changes, pause, index); }
    }
}