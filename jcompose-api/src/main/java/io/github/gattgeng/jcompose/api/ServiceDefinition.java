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
    /** Returns the service's port mappings in Docker Compose short or long syntax. */
    public List<ComposeServicePort> ports() {
        Object value = configuration.get("ports");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(ServiceDefinition::port).toList();
    }
    public List<String> profiles() {
        Object value = configuration.get("profiles");
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of();
    }
    private String string(String key) {
        Object value = configuration.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static ComposeServicePort port(Object value) {
        if (value instanceof Map<?, ?> mapping) {
            return new ComposeServicePort(
                    text(mapping.get("target")), text(mapping.get("published")),
                    text(mapping.get("host_ip")), defaultText(mapping.get("protocol"), "tcp"),
                    text(mapping.get("mode")), text(mapping.get("name")), text(mapping.get("app_protocol")));
        }
        String specification = String.valueOf(value);
        int slash = specification.lastIndexOf('/');
        String protocol = slash < 0 ? "tcp" : specification.substring(slash + 1);
        String ports = slash < 0 ? specification : specification.substring(0, slash);
        int targetSeparator = ports.lastIndexOf(':');
        String target = targetSeparator < 0 ? ports : ports.substring(targetSeparator + 1);
        String published = null;
        String hostIp = null;
        if (targetSeparator >= 0) {
            String hostAndPublished = ports.substring(0, targetSeparator);
            int publishedSeparator = hostAndPublished.lastIndexOf(':');
            if (publishedSeparator >= 0) {
                hostIp = hostAndPublished.substring(0, publishedSeparator);
                published = hostAndPublished.substring(publishedSeparator + 1);
            } else {
                published = hostAndPublished;
            }
        }
        return new ComposeServicePort(target, published, hostIp, protocol, null, null, null);
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static String defaultText(Object value, String fallback) {
        String text = text(value);
        return text == null ? fallback : text;
    }
}
