package io.github.gattgeng.jcompose.api;

import java.util.Objects;

/**
 * Options for publishing a Compose application as an OCI artifact.
 * @param resolveImageDigests Pin referenced image tags to content digests.
 * @param withEnvironment Include referenced service environment files as OCI layers.
 * @param ocIVersion OCI image/artifact specification version to use.
 * @param insecureRegistry Allow insecure registry transport.
 */
public record ComposePublishOptions(
        boolean resolveImageDigests,
        boolean withEnvironment,
        String ocIVersion,
        boolean insecureRegistry) {
    public ComposePublishOptions {
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private boolean resolveImageDigests;
        private boolean withEnvironment;
        private String ocIVersion;
        private boolean insecureRegistry;

        /**
         * Pin referenced image tags to content digests.
         * @param value Pin referenced image tags to content digests.
         * @return this builder
         */
        public Builder resolveImageDigests(boolean value) { this.resolveImageDigests = value; return this; }

        /**
         * Include referenced service environment files as OCI layers.
         * @param value Request environment-file layers in the published artifact.
         * @return this builder
         */
        public Builder withEnvironment(boolean value) { this.withEnvironment = value; return this; }

        /**
         * OCI image-spec version the published artifacts should target.
         * @param value OCI image-spec version the published artifacts should target.
         * @return this builder
         */
        public Builder ocIVersion(String value) { this.ocIVersion = value; return this; }

        /**
         * Allow insecure registry transport.
         * @param value Allow insecure registry transport.
         * @return this builder
         */
        public Builder insecureRegistry(boolean value) { this.insecureRegistry = value; return this; }

        /** @return the immutable operation options */
        public ComposePublishOptions build() { return new ComposePublishOptions(resolveImageDigests, withEnvironment, ocIVersion, insecureRegistry); }
    }
}
