package io.github.gattgeng.jcompose.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Parsed Compose document. Service configuration is retained as a normalized, read-only mapping. */
public record ComposeProject(Path sourceFile, Map<String, ServiceDefinition> services,
                             List<String> volumes, List<String> networks,
                             List<String> secrets, List<String> configs,
                             Map<String, Object> extensions) {
    public ComposeProject {
        services = Map.copyOf(services);
        volumes = List.copyOf(volumes);
        networks = List.copyOf(networks);
        secrets = List.copyOf(secrets);
        configs = List.copyOf(configs);
        extensions = Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
    }
}
