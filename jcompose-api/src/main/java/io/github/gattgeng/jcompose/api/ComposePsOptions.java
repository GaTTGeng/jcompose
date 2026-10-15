package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for listing the project's containers.
 * @param services Restricts the listing to these service names; null covers every project service.
 * @param all Include stopped containers, not only running ones.
 * @param format Output format template applied to each container row.
 * @param status Only include containers in this state (for example <code>running</code> or <code>exited</code>).
 * @param quiet Return only container IDs, omitting summary details.
 * @param filter Filter expression applied to the container listing.
 */
public record ComposePsOptions(
        List<String> services,
        boolean all,
        String format,
        String status,
        boolean quiet,
        String filter) {
    public ComposePsOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean all;
        private String format;
        private String status;
        private boolean quiet;
        private String filter;

        /**
         * Restricts the listing to these service names; null covers every project service.
         * @param value Restricts the listing to these service names; null covers every project service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Include stopped containers, not only running ones.
         * @param value Include stopped containers, not only running ones.
         * @return this builder
         */
        public Builder all(boolean value) { this.all = value; return this; }

        /**
         * Output format template applied to each container row.
         * @param value Output format template applied to each container row.
         * @return this builder
         */
        public Builder format(String value) { this.format = value; return this; }

        /**
         * Only include containers in this state (for example <code>running</code> or <code>exited</code>).
         * @param value Only include containers in this state (for example <code>running</code> or <code>exited</code>).
         * @return this builder
         */
        public Builder status(String value) { this.status = value; return this; }

        /**
         * Return only container IDs, omitting summary details.
         * @param value Return only container IDs, omitting summary details.
         * @return this builder
         */
        public Builder quiet(boolean value) { this.quiet = value; return this; }

        /**
         * Filter expression applied to the container listing.
         * @param value Filter expression applied to the container listing.
         * @return this builder
         */
        public Builder filter(String value) { this.filter = value; return this; }

        /** @return the immutable operation options */
        public ComposePsOptions build() { return new ComposePsOptions(services, all, format, status, quiet, filter); }
    }
}