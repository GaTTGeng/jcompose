package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** One Compose service. The raw normalized mapping preserves fields the engine does not apply. */
public record ServiceDefinition(String name, Map<String, Object> configuration) {
    public ServiceDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        configuration = Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }
    public String image() { return string("image"); }
    public Object build() { return configuration.get("build"); }
    public List<String> profiles() {
        Object value = configuration.get("profiles");
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of();
    }
    private String string(String key) {
        Object value = configuration.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
