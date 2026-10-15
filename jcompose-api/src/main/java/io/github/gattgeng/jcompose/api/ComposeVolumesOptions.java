package io.github.gattgeng.jcompose.api;

import java.util.List;

/**
 * Options for listing volumes owned by the project.
 * @param services Restricts the listing to these service names; null covers every project service.
 */
public record ComposeVolumesOptions(
        List<String> services) {
    public ComposeVolumesOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;

        /**
         * Restricts the listing to these service names; null covers every project service.
         * @param value Restricts the listing to these service names; null covers every project service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /** @return the immutable operation options */
        public ComposeVolumesOptions build() { return new ComposeVolumesOptions(services); }
    }
}