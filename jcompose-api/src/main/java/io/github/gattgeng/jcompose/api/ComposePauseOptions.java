package io.github.gattgeng.jcompose.api;

import java.util.List;

/**
 * Options selecting which services to pause.
 * @param services Restricts the pause to these service names; null covers every project service.
 */
public record ComposePauseOptions(
        List<String> services) {
    public ComposePauseOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;

        /**
         * Restricts the pause to these service names; null covers every project service.
         * @param value Restricts the pause to these service names; null covers every project service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /** @return the immutable operation options */
        public ComposePauseOptions build() { return new ComposePauseOptions(services); }
    }
}