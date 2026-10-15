package io.github.gattgeng.jcompose.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps commit options to Docker Engine container-commit parameters. */
final class ImageCommitParametersFactory {
    private ImageCommitParametersFactory() { }

    static List<String> toDockerfileChanges(Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) return List.of();
        List<String> result = new ArrayList<>(changes.size());
        for (var entry : changes.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank())
                throw new IllegalArgumentException("Commit change keys must be non-empty Dockerfile instructions.");
            result.add(value == null || value.isBlank() ? key.trim() : key.trim() + " " + value);
        }
        return List.copyOf(result);
    }
}
