package io.github.gattgeng.jcompose.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Parsed Compose document with immutable service and supported resource definitions. */
public record ComposeProject(Path sourceFile, Map<String, ServiceDefinition> services,
                             List<String> volumes, List<String> networks,
                             List<String> secrets, List<String> configs,
                             Map<String, Object> volumeDefinitions, Map<String, Object> networkDefinitions,
                             Map<String, Object> secretDefinitions, Map<String, Object> configDefinitions,
                             Map<String, Object> extensions, Map<String, String> environment,
                             List<ComposeDiagnostic> diagnostics) {
    public ComposeProject(Path sourceFile, Map<String, ServiceDefinition> services,
                          List<String> volumes, List<String> networks, List<String> secrets, List<String> configs,
                          Map<String, Object> volumeDefinitions, Map<String, Object> networkDefinitions,
                          Map<String, Object> secretDefinitions, Map<String, Object> configDefinitions,
                          Map<String, Object> extensions, Map<String, String> environment) {
        this(sourceFile, services, volumes, networks, secrets, configs, volumeDefinitions, networkDefinitions,
                secretDefinitions, configDefinitions, extensions, environment, List.of());
    }

    public ComposeProject(Path sourceFile, Map<String, ServiceDefinition> services,
                          List<String> volumes, List<String> networks,
                          List<String> secrets, List<String> configs,
                          Map<String, Object> volumeDefinitions, Map<String, Object> networkDefinitions,
                          Map<String, Object> secretDefinitions, Map<String, Object> configDefinitions,
                          Map<String, Object> extensions) {
        this(sourceFile, services, volumes, networks, secrets, configs, volumeDefinitions, networkDefinitions,
                secretDefinitions, configDefinitions, extensions, Map.of(), List.of());
    }

    public ComposeProject(Path sourceFile, Map<String, ServiceDefinition> services,
                          List<String> volumes, List<String> networks,
                          List<String> secrets, List<String> configs,
                          Map<String, Object> volumeDefinitions, Map<String, Object> networkDefinitions,
                          Map<String, Object> extensions) {
        this(sourceFile, services, volumes, networks, secrets, configs, volumeDefinitions, networkDefinitions,
                Map.of(), Map.of(), extensions, Map.of(), List.of());
    }

    public ComposeProject(Path sourceFile, Map<String, ServiceDefinition> services,
                          List<String> volumes, List<String> networks,
                          List<String> secrets, List<String> configs,
                          Map<String, Object> extensions) {
        this(sourceFile, services, volumes, networks, secrets, configs, Map.of(), Map.of(), Map.of(), Map.of(),
                extensions, Map.of(), List.of());
    }

    public ComposeProject {
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        volumes = List.copyOf(volumes);
        networks = List.copyOf(networks);
        secrets = List.copyOf(secrets);
        configs = List.copyOf(configs);
        volumeDefinitions = freezeMap(volumeDefinitions);
        networkDefinitions = freezeMap(networkDefinitions);
        secretDefinitions = freezeMap(secretDefinitions);
        configDefinitions = freezeMap(configDefinitions);
        extensions = Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    private static Map<String, Object> freezeMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, freeze(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> result.put(String.valueOf(key), freeze(nested)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) return list.stream().map(ComposeProject::freeze).toList();
        return value;
    }
}
