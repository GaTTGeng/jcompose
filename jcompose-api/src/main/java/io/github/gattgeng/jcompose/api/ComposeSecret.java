package io.github.gattgeng.jcompose.api;

import java.util.Map;

/** Typed projection of a top-level Compose secret definition. */
public record ComposeSecret(String name, String file, String environment, boolean external,
                            Map<String, String> labels) {
    public ComposeSecret {
        labels = labels == null ? null : Map.copyOf(labels);
    }
}
