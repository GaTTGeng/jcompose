package io.github.gattgeng.jcompose.api;

import java.util.Map;

/** Typed projection of a top-level Compose config definition. */
public record ComposeConfig(String name, String file, String environment, boolean external,
                            Map<String, String> labels) {
    public ComposeConfig {
        labels = labels == null ? null : Map.copyOf(labels);
    }
}
