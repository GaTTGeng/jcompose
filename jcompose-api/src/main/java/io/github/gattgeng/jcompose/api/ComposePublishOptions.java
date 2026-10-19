package io.github.gattgeng.jcompose.api;

import java.util.Objects;

/**
 * Options for publishing a Compose application as an OCI artifact.
 * @param resolveImageDigests Prefer content digests when resolving image references.
 * @param withEnvironment Include service environment configuration alongside the tagged image.
 * @param ocIVersion OCI image-spec version the published artifacts should target.
 * @param insecureRegistry Allow tagging/pushing against a registry without TLS verification.
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
         * Prefer content digests when resolving image references.
         * @param value Prefer content digests when resolving image references.
         * @return this builder
         */
        public Builder resolveImageDigests(boolean value) { this.resolveImageDigests = value; return this; }

        /**
         * Include service environment configuration alongside the tagged image.
         * @param value Include service environment configuration alongside the tagged image.
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
         * Allow tagging/pushing against a registry without TLS verification.
         * @param value Allow tagging/pushing against a registry without TLS verification.
         * @return this builder
         */
        public Builder insecureRegistry(boolean value) { this.insecureRegistry = value; return this; }

        /** @return the immutable operation options */
        public ComposePublishOptions build() { return new ComposePublishOptions(resolveImageDigests, withEnvironment, ocIVersion, insecureRegistry); }
    }
}
