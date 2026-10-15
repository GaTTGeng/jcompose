package io.github.gattgeng.jcompose.api;

import java.util.List;

/**
 * Options for listing processes inside the project's containers.
 * @param services Restricts the listing to these service names. Null covers every project service; an empty list selects no services and yields an empty listing.
 */
public record ComposeTopOptions(
        List<String> services) {
    public ComposeTopOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;

        /**
         * Restricts the listing to these service names. Null covers every project service; an empty list selects no services and yields an empty listing.
         * @param value Restricts the listing to these service names. Null covers every project service; an empty list selects no services and yields an empty listing.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /** @return the immutable operation options */
        public ComposeTopOptions build() { return new ComposeTopOptions(services); }
    }
}