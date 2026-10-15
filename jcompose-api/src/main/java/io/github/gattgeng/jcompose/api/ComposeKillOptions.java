package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for sending a signal to the project's containers.
 * @param services Restricts the signal to these service names; null signals every project service.
 * @param signal Signal name or number sent to the container processes.
 * @param removeOrphans Also signal containers of services that no longer exist in the Compose file.
 */
public record ComposeKillOptions(
        List<String> services,
        String signal,
        boolean removeOrphans) {
    public ComposeKillOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private String signal = "SIGKILL";
        private boolean removeOrphans;

        /**
         * Restricts the signal to these service names; null signals every project service.
         * @param value Restricts the signal to these service names; null signals every project service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Signal name or number sent to the container processes.
         * @param value Signal name or number sent to the container processes.
         * @return this builder
         */
        public Builder signal(String value) { this.signal = value; return this; }

        /**
         * Also signal containers of services that no longer exist in the Compose file.
         * @param value Also signal containers of services that no longer exist in the Compose file.
         * @return this builder
         */
        public Builder removeOrphans(boolean value) { this.removeOrphans = value; return this; }

        /** @return the immutable operation options */
        public ComposeKillOptions build() { return new ComposeKillOptions(services, signal, removeOrphans); }
    }
}