package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options filtering the project event stream.
 * @param services Only report events for these service names. Null selects services enabled by the context profiles and keeps project-level network/volume events; a non-null list selects those services and drops events with no service label. An empty list is a no-op and yields no events.
 * @param json Request JSON-shaped event payloads.
 * @param onSubscribed Invoked only after the Docker event response has been established and project resources have been resolved. Callers that race a lifecycle operation against the stream can await this signal before triggering that operation; the callback is not invoked when subscription setup fails.
 */
public record ComposeEventsOptions(
        List<String> services,
        boolean json,
        Runnable onSubscribed) {
    public ComposeEventsOptions {
        services = services == null ? null : List.copyOf(services);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private boolean json;
        private Runnable onSubscribed;

        /**
         * Only report events for these service names. Null selects services enabled by the context profiles and keeps project-level network/volume events; a non-null list selects those services and drops events with no service label. An empty list is a no-op and yields no events.
         * @param value Only report events for these service names. Null selects services enabled by the context profiles and keeps project-level network/volume events; a non-null list selects those services and drops events with no service label. An empty list is a no-op and yields no events.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Request JSON-shaped event payloads.
         * @param value Request JSON-shaped event payloads.
         * @return this builder
         */
        public Builder json(boolean value) { this.json = value; return this; }

        /**
         * Invoked only after the Docker event response has been established and project resources have been resolved. Callers that race a lifecycle operation against the stream can await this signal before triggering that operation; the callback is not invoked when subscription setup fails.
         * @param value Invoked only after the Docker event response has been established and project resources have been resolved. Callers that race a lifecycle operation against the stream can await this signal before triggering that operation; the callback is not invoked when subscription setup fails.
         * @return this builder
         */
        public Builder onSubscribed(Runnable value) { this.onSubscribed = value; return this; }

        /** @return the immutable operation options */
        public ComposeEventsOptions build() { return new ComposeEventsOptions(services, json, onSubscribed); }
    }
}
