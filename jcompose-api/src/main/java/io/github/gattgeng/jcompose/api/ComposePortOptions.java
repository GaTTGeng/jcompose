package io.github.gattgeng.jcompose.api;

import java.util.Objects;

/**
 * Options for resolving a published container port.
 * @param protocol Transport protocol of the container port.
 * @param index One-based replica number from the container label; null selects the lowest running replica. One-off containers are excluded.
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
         * Selects a running service replica by its one-based container-number label.
         * @param value replica number, or null for the lowest running replica; one-off containers are excluded
         * @return this builder
         */
        public Builder index(Integer value) { this.index = value; return this; }

        /** @return the immutable operation options */
        public ComposePortOptions build() { return new ComposePortOptions(protocol, index); }
    }
}
