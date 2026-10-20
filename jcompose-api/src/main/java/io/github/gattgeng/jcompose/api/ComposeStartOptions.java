package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Objects;

/**
 * Options for starting already-created containers.
 * @param services Restricts the start to these service names; null starts every project service.
 * @param attach Receives output from the started containers; null does not attach.
 * @param attachTo Output streams to attach to when {@link #attach()} is set; supported values are <code>stdout</code> and <code>stderr</code>, and null attaches both.
 * @param waitForCompletion Block until the started containers are running (or healthy), instead of returning once start is issued.
 * @param waitTimeoutSeconds Upper bound in seconds for {@link #waitForCompletion()} before the call fails.
 * @param dependencyWaitTimeoutSeconds Maximum seconds to wait for each dependency to become ready; null waits until readiness, failure, or cancellation.
 */
public record ComposeStartOptions(
        List<String> services,
        ILogConsumer attach,
        List<String> attachTo,
        boolean waitForCompletion,
        Integer waitTimeoutSeconds,
        Integer dependencyWaitTimeoutSeconds) {
    public ComposeStartOptions {
        services = services == null ? null : List.copyOf(services);
        attachTo = attachTo == null ? null : List.copyOf(attachTo);
    }

    public static Builder builder() { return new Builder(); }

    /** Fluent builder for constructing operation options with Compose defaults. */
    public static final class Builder {
        private List<String> services;
        private ILogConsumer attach;
        private List<String> attachTo;
        private boolean waitForCompletion;
        private Integer waitTimeoutSeconds;
        private Integer dependencyWaitTimeoutSeconds;

        /**
         * Restricts the start to these service names; null starts every project service.
         * @param value Restricts the start to these service names; null starts every project service.
         * @return this builder
         */
        public Builder services(List<String> value) { this.services = value; return this; }

        /**
         * Receives output from the started containers; null does not attach.
         * @param value Receives output from the started containers; null does not attach.
         * @return this builder
         */
        public Builder attach(ILogConsumer value) { this.attach = value; return this; }

        /**
         * Output streams to attach to when {@link ComposeStartOptions#attach()} is set; supported values are <code>stdout</code> and <code>stderr</code>, and null attaches both.
         * @param value Output streams to attach to when {@link ComposeStartOptions#attach()} is set; supported values are <code>stdout</code> and <code>stderr</code>, and null attaches both.
         * @return this builder
         */
        public Builder attachTo(List<String> value) { this.attachTo = value; return this; }

        /**
         * Block until the started containers are running (or healthy), instead of returning once start is issued.
         * @param value Block until the started containers are running (or healthy), instead of returning once start is issued.
         * @return this builder
         */
        public Builder waitForCompletion(boolean value) { this.waitForCompletion = value; return this; }

        /**
         * Upper bound in seconds for {@link ComposeStartOptions#waitForCompletion()} before the call fails.
         * @param value Upper bound in seconds for {@link ComposeStartOptions#waitForCompletion()} before the call fails.
         * @return this builder
         */
        public Builder waitTimeoutSeconds(Integer value) { this.waitTimeoutSeconds = value; return this; }

        /**
         * Maximum seconds to wait for each dependency to become ready; null waits until readiness, failure, or cancellation.
         * @param value Maximum seconds to wait for each dependency to become ready; null waits until readiness, failure, or cancellation.
         * @return this builder
         */
        public Builder dependencyWaitTimeoutSeconds(Integer value) { this.dependencyWaitTimeoutSeconds = value; return this; }

        /** @return the immutable operation options */
        public ComposeStartOptions build() { return new ComposeStartOptions(services, attach, attachTo, waitForCompletion, waitTimeoutSeconds, dependencyWaitTimeoutSeconds); }
    }
}
