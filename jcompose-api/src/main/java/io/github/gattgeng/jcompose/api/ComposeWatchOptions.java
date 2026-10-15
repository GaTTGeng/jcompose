package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for observing build-context changes of selected project services.
 * @param services Restricts observation to these service names. Null uses active-profile selection, an empty list observes nothing, and unknown names are rejected. Explicit names bypass profile filtering.
 * @param noUp Compatibility option with no effect because observation never starts the project.
 * @param quiet Compatibility option with no effect because observation writes no progress output.
 * @param prune Requests pruning, which is unsupported by the notification-only observer.
 */
public record ComposeWatchOptions(
        List<String> services,
        boolean noUp,
        boolean quiet,
        boolean prune) {
    public ComposeWatchOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean noUp;
        private boolean quiet;
        private boolean prune;

        /**
         * Restricts observation to these service names. Null uses active-profile selection, an empty list observes nothing, and unknown names are rejected. Explicit names bypass profile filtering.
         * @param value Restricts observation to these service names. Null uses active-profile selection, an empty list observes nothing, and unknown names are rejected. Explicit names bypass profile filtering.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Compatibility option with no effect because observation never starts the project.
         * @param value Compatibility option with no effect because observation never starts the project.
         * @return this builder
         */
        public Builder noUp(boolean value) { this.noUp = value; return this; }

        /**
         * Compatibility option with no effect because observation writes no progress output.
         * @param value Compatibility option with no effect because observation writes no progress output.
         * @return this builder
         */
        public Builder quiet(boolean value) { this.quiet = value; return this; }

        /**
         * Requests pruning, which is unsupported by the notification-only observer.
         * @param value Requests pruning, which is unsupported by the notification-only observer.
         * @return this builder
         */
        public Builder prune(boolean value) { this.prune = value; return this; }

        /** @return the immutable operation options */
        public ComposeWatchOptions build() { return new ComposeWatchOptions(services, noUp, quiet, prune); }
    }
}