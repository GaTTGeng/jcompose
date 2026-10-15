package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for listing images used by the project.
 * @param services Restricts the listing to these service names; null covers every project service.
 * @param format Output format template applied to each image row.
 * @param quiet Return only image IDs, omitting summary details.
 */
public record ComposeImagesOptions(
        List<String> services,
        String format,
        boolean quiet) {
    public ComposeImagesOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private String format;
        private boolean quiet;

        /**
         * Restricts the listing to these service names; null covers every project service.
         * @param value Restricts the listing to these service names; null covers every project service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Output format template applied to each image row.
         * @param value Output format template applied to each image row.
         * @return this builder
         */
        public Builder format(String value) { this.format = value; return this; }

        /**
         * Return only image IDs, omitting summary details.
         * @param value Return only image IDs, omitting summary details.
         * @return this builder
         */
        public Builder quiet(boolean value) { this.quiet = value; return this; }

        /** @return the immutable operation options */
        public ComposeImagesOptions build() { return new ComposeImagesOptions(services, format, quiet); }
    }
}