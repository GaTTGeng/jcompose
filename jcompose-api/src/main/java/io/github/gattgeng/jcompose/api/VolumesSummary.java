package io.github.gattgeng.jcompose.api;

import java.util.Map;

/** Summary of one volume owned by the project. */
public record VolumesSummary(String name, String driver, String mountpoint,
                             Map<String, String> labels, String scope,
                             Map<String, String> options, String createdAt) {
    public VolumesSummary {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        options = options == null ? null : Map.copyOf(options);
    }
}
