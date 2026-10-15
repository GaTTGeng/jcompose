package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for waiting on container exit of the project.
 * @param services Restricts the wait to these service names; null or empty selects services from the context's active profiles.
 * @param downProjectOnContainerExit Tear the whole project down as soon as any watched container exits.
 */
public record ComposeWaitOptions(
        List<String> services,
        boolean downProjectOnContainerExit) {
    public ComposeWaitOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean downProjectOnContainerExit;

        /**
         * Restricts the wait to these service names; null waits for every project container.
         * @param value Restricts the wait to these service names; null waits for every project container.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Tear the whole project down as soon as any watched container exits.
         * @param value Tear the whole project down as soon as any watched container exits.
         * @return this builder
         */
        public Builder downProjectOnContainerExit(boolean value) { this.downProjectOnContainerExit = value; return this; }

        /** @return the immutable operation options */
        public ComposeWaitOptions build() { return new ComposeWaitOptions(services, downProjectOnContainerExit); }
    }
}
