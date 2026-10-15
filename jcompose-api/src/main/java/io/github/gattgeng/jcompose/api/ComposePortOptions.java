package io.github.gattgeng.jcompose.api;

import java.util.Objects;

/**
 * Options for resolving a published container port.
 * @param protocol Transport protocol of the container port.
 * @param index Replica index within the service; null resolves the first matching container.
 */
public record ComposePortOptions(
        String protocol,
        Integer index) {
    public ComposePortOptions {
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private String protocol = "tcp";
        private Integer index;

        /**
         * Transport protocol of the container port.
         * @param value Transport protocol of the container port.
         * @return this builder
         */
        public Builder protocol(String value) { this.protocol = value; return this; }

        /**
         * Replica index within the service; null resolves the first matching container.
         * @param value Replica index within the service; null resolves the first matching container.
         * @return this builder
         */
        public Builder index(Integer value) { this.index = value; return this; }

        /** @return the immutable operation options */
        public ComposePortOptions build() { return new ComposePortOptions(protocol, index); }
    }
}