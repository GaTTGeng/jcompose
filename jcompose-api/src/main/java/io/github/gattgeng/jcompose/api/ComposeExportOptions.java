package io.github.gattgeng.jcompose.api;

import java.util.Objects;

/**
 * Options for exporting a service container's filesystem to a local archive.
 * @param service Service whose container filesystem is exported.
 * @param outputPath Host path the archive is written to.
 * @param index Replica index within the service; null exports the first matching container.
 */
public record ComposeExportOptions(
        String service,
        String outputPath,
        Integer index) {
    public ComposeExportOptions {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(outputPath, "outputPath");
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private String service;
        private String outputPath;
        private Integer index;

        /**
         * Service whose container filesystem is exported.
         * @param value Service whose container filesystem is exported.
         * @return this builder
         */
        public Builder service(String value) { this.service = value; return this; }

        /**
         * Host path the archive is written to.
         * @param value Host path the archive is written to.
         * @return this builder
         */
        public Builder outputPath(String value) { this.outputPath = value; return this; }

        /**
         * Replica index within the service; null exports the first matching container.
         * @param value Replica index within the service; null exports the first matching container.
         * @return this builder
         */
        public Builder index(Integer value) { this.index = value; return this; }

        /** @return the immutable operation options */
        public ComposeExportOptions build() { return new ComposeExportOptions(service, outputPath, index); }
    }
}