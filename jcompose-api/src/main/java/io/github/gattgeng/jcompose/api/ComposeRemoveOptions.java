package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for removing the project's containers.
 * @param services Restricts the removal to these service names; null removes every project service's containers.
 * @param force Remove containers even if they cannot be stopped cleanly.
 * @param stop Stop running containers before removing them.
 * @param volumes Also remove anonymous volumes attached to the containers.
 */
public record ComposeRemoveOptions(
        List<String> services,
        boolean force,
        boolean stop,
        boolean volumes) {
    public ComposeRemoveOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean force;
        private boolean stop;
        private boolean volumes;

        /**
         * Restricts the removal to these service names; null removes every project service's containers.
         * @param value Restricts the removal to these service names; null removes every project service's containers.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Remove containers even if they cannot be stopped cleanly.
         * @param value Remove containers even if they cannot be stopped cleanly.
         * @return this builder
         */
        public Builder force(boolean value) { this.force = value; return this; }

        /**
         * Stop running containers before removing them.
         * @param value Stop running containers before removing them.
         * @return this builder
         */
        public Builder stop(boolean value) { this.stop = value; return this; }

        /**
         * Also remove anonymous volumes attached to the containers.
         * @param value Also remove anonymous volumes attached to the containers.
         * @return this builder
         */
        public Builder volumes(boolean value) { this.volumes = value; return this; }

        /** @return the immutable operation options */
        public ComposeRemoveOptions build() { return new ComposeRemoveOptions(services, force, stop, volumes); }
    }
}