package io.github.gattgeng.jcompose.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.ArrayList;

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

    /** Returns typed top-level volume definitions in declaration order. */
    public List<ComposeVolume> volumeConfigurations() {
        return volumeDefinitions.entrySet().stream().map(entry -> {
            Map<?, ?> definition = mapping(entry.getValue());
            return new ComposeVolume(entry.getKey(), external(definition.get("external")),
                    text(definition.get("driver")), stringMap(definition.get("labels")),
                    stringMap(definition.get("driver_opts")));
        }).toList();
    }

    /** Returns typed top-level network definitions in declaration order. */
    public List<ComposeNetwork> networkConfigurations() {
        return networkDefinitions.entrySet().stream().map(entry -> {
            Map<?, ?> definition = mapping(entry.getValue());
            return new ComposeNetwork(entry.getKey(), external(definition.get("external")),
                    text(definition.get("driver")), bool(definition.get("internal")),
                    bool(definition.get("attachable")), stringMap(definition.get("labels")),
                    stringMap(definition.get("driver_opts")), ipam(definition.get("ipam")),
                    bool(definition.get("enable_ipv6")), text(definition.get("scope")));
        }).toList();
    }

    /** Returns typed top-level secret definitions in declaration order. */
    public List<ComposeSecret> secretConfigurations() {
        return secretDefinitions.entrySet().stream().map(entry -> {
            Map<?, ?> definition = mapping(entry.getValue());
            return new ComposeSecret(entry.getKey(), text(definition.get("file")),
                    text(definition.get("environment")), external(definition.get("external")),
                    stringMap(definition.get("labels")));
        }).toList();
    }

    /** Returns typed top-level config definitions in declaration order. */
    public List<ComposeConfig> configConfigurations() {
        return configDefinitions.entrySet().stream().map(entry -> {
            Map<?, ?> definition = mapping(entry.getValue());
            return new ComposeConfig(entry.getKey(), text(definition.get("file")),
                    text(definition.get("environment")), external(definition.get("external")),
                    stringMap(definition.get("labels")));
        }).toList();
    }

    private static IpamConfig ipam(Object value) {
        if (!(value instanceof Map<?, ?> mapping)) return null;
        List<IpamSubnetConfig> subnets = null;
        if (mapping.get("config") instanceof List<?> entries) {
            subnets = new ArrayList<>();
            for (Object entry : entries) {
                Map<?, ?> subnet = mapping(entry);
                subnets.add(new IpamSubnetConfig(text(subnet.get("subnet")), text(subnet.get("gateway")),
                        text(subnet.get("ip_range"))));
            }
        }
        return new IpamConfig(text(mapping.get("driver")), subnets);
    }

    private static Map<?, ?> mapping(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static boolean external(Object value) {
        return Boolean.TRUE.equals(bool(value)) || value instanceof Map<?, ?>;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean result ? result : null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> mapping)) return null;
        Map<String, String> result = new LinkedHashMap<>();
        mapping.forEach((key, entry) -> result.put(String.valueOf(key), entry == null ? "" : String.valueOf(entry)));
        return Collections.unmodifiableMap(result);
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
