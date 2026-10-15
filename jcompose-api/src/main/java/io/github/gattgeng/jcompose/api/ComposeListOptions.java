package io.github.gattgeng.jcompose.api;

import java.util.Objects;

/**
 * Options for listing Compose projects known to the Docker daemon.
 * @param all Include projects with containers excluded by Docker's default listing.
 * @param format Output format template applied to each project row.
 * @param filter Filter expression applied to the project listing.
 * @param quiet Return only project names, omitting summary details.
 */
public record ComposeListOptions(
        boolean all,
        String format,
        String filter,
        boolean quiet) {
    public ComposeListOptions {
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private boolean all;
        private String format;
        private String filter;
        private boolean quiet;

        /**
         * Include projects with containers excluded by Docker's default listing.
         * @param value Include projects with containers excluded by Docker's default listing.
         * @return this builder
         */
        public Builder all(boolean value) { this.all = value; return this; }

        /**
         * Output format template applied to each project row.
         * @param value Output format template applied to each project row.
         * @return this builder
         */
        public Builder format(String value) { this.format = value; return this; }

        /**
         * Filter expression applied to the project listing.
         * @param value Filter expression applied to the project listing.
         * @return this builder
         */
        public Builder filter(String value) { this.filter = value; return this; }

        /**
         * Return only project names, omitting summary details.
         * @param value Return only project names, omitting summary details.
         * @return this builder
         */
        public Builder quiet(boolean value) { this.quiet = value; return this; }

        /** @return the immutable operation options */
        public ComposeListOptions build() { return new ComposeListOptions(all, format, filter, quiet); }
    }
}