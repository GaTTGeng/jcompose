package io.github.gattgeng.jcompose.api;

import java.util.Map;

/** Typed projection of a top-level Compose volume definition. */
public record ComposeVolume(String name, boolean external, String driver,
                            Map<String, String> labels, Map<String, String> driverOpts) {
    public ComposeVolume {
        labels = immutableMap(labels);
        driverOpts = immutableMap(driverOpts);
    }

    private static Map<String, String> immutableMap(Map<String, String> values) {
        return values == null ? null : Map.copyOf(values);
    }
}
