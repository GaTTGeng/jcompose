package io.github.gattgeng.jcompose.api;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

/** One observed event about a project resource. */
public record ComposeEvent(Instant timestamp, String type, String action, String id,
                           String service, String container, Map<String, String> attributes) {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneId.systemDefault());

    public ComposeEvent {
        timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        attributes = attributes == null ? null : Map.copyOf(attributes);
    }

    @Override public String toString() {
        String suffix = attributes == null || attributes.isEmpty() ? ""
                : attributes.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", ", " (", ")"));
        return DISPLAY_TIME.format(timestamp) + " " + type + " " + action + " " + id + suffix;
    }
}
