package io.github.gattgeng.jcompose.api;

import java.util.Objects;

/**
 * Options for attaching to a service container's output streams.
 * @param serviceName Service to attach to; null selects the project's single running service container.
 * @param index Replica index within the service; null picks the first matching container.
 * @param stdin Also forward standard input to the container.
 * @param stdout Include the container's standard output stream.
 * @param stderr Include the container's standard error stream.
 * @param detachKeys Key sequence that detaches the client, in Docker's detach-keys format.
 */
public record ComposeAttachOptions(
        String serviceName,
        Integer index,
        boolean stdin,
        boolean stdout,
        boolean stderr,
        String detachKeys) {
    public ComposeAttachOptions {
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private String serviceName;
        private Integer index;
        private boolean stdin;
        private boolean stdout = true;
        private boolean stderr = true;
        private String detachKeys;

        /**
         * Service to attach to; null selects the project's single running service container.
         * @param value Service to attach to; null selects the project's single running service container.
         * @return this builder
         */
        public Builder serviceName(String value) { this.serviceName = value; return this; }

        /**
         * Replica index within the service; null picks the first matching container.
         * @param value Replica index within the service; null picks the first matching container.
         * @return this builder
         */
        public Builder index(Integer value) { this.index = value; return this; }

        /**
         * Also forward standard input to the container.
         * @param value Also forward standard input to the container.
         * @return this builder
         */
        public Builder stdin(boolean value) { this.stdin = value; return this; }

        /**
         * Include the container's standard output stream.
         * @param value Include the container's standard output stream.
         * @return this builder
         */
        public Builder stdout(boolean value) { this.stdout = value; return this; }

        /**
         * Include the container's standard error stream.
         * @param value Include the container's standard error stream.
         * @return this builder
         */
        public Builder stderr(boolean value) { this.stderr = value; return this; }

        /**
         * Key sequence that detaches the client, in Docker's detach-keys format.
         * @param value Key sequence that detaches the client, in Docker's detach-keys format.
         * @return this builder
         */
        public Builder detachKeys(String value) { this.detachKeys = value; return this; }

        /** @return the immutable operation options */
        public ComposeAttachOptions build() { return new ComposeAttachOptions(serviceName, index, stdin, stdout, stderr, detachKeys); }
    }
}