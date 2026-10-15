package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Map;

/** Loaded Compose project summary and normalized YAML snapshot. */
public record ComposeProjectConfig(String name, String workingDirectory, List<String> configFiles,
                                   List<String> services, List<String> networks, List<String> volumes,
                                   List<String> secrets, List<String> configs,
                                   Map<String, String> environment, String renderedYaml) {
    public ComposeProjectConfig {
        configFiles = configFiles == null ? List.of() : List.copyOf(configFiles);
        services = services == null ? List.of() : List.copyOf(services);
        networks = networks == null ? List.of() : List.copyOf(networks);
        volumes = volumes == null ? List.of() : List.copyOf(volumes);
        secrets = secrets == null ? List.of() : List.copyOf(secrets);
        configs = configs == null ? List.of() : List.copyOf(configs);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
