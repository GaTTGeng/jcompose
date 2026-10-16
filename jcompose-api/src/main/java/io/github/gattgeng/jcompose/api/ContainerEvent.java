package io.github.gattgeng.jcompose.api;

/** One container lifecycle event delivered to an {@link IContainerEventListener}. */
public record ContainerEvent(
        /** Event type, for example {@code start}, {@code exit}, or {@code log}. */
        String type,
        /** Event time as a Unix timestamp in milliseconds. */
        long timestamp,
        /** Container the event refers to. */
        String containerId,
        /** Compose service the container belongs to. */
        String serviceName,
        /** Log line associated with the event, when the event carries output. */
        String line,
        /** Process exit code, present on exit events. */
        Integer exitCode,
        /** Whether the container is being restarted rather than staying stopped. */
        boolean restarting) {
}
