package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProject;
import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Renders a normalized Compose document from fields retained by the loader. */
final class ComposeConfigRenderer {
    private ComposeConfigRenderer() { }

    static String render(ComposeProject project, List<ServiceDefinition> services, String projectName) {
        return render(project, services, projectName, true);
    }

    static String render(ComposeProject project, List<ServiceDefinition> services, String projectName,
                         boolean withEnvironment) {
        return render(project, services, projectName, withEnvironment, null);
    }

    static String render(ComposeProject project, List<ServiceDefinition> services, String projectName,
                         boolean withEnvironment, Map<String, String> envFileAliases) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", projectName);

        Map<String, Object> renderedServices = new LinkedHashMap<>();
        for (ServiceDefinition service : services) {
            Map<String, Object> configuration = new LinkedHashMap<>(service.configuration());
            // Environment files are omitted from snapshots unless their contents are packaged
            // as OCI layers and their paths are rewritten to those packaged layer names.
            Object envFile = configuration.get("env_file");
            if (envFileAliases == null) configuration.remove("env_file");
            else if (envFile != null) configuration.put("env_file", rewriteEnvFile(envFile, envFileAliases));
            if (!withEnvironment) configuration.remove("environment");
            renderedServices.put(service.name(), configuration);
        }
        root.put("services", renderedServices);

        addResources(root, "networks", project.networks(), project.networkDefinitions());
        addResources(root, "volumes", project.volumes(), project.volumeDefinitions());
        addResources(root, "secrets", project.secrets(), project.secretDefinitions());
        addResources(root, "configs", project.configs(), project.configDefinitions());
        new TreeMap<>(project.extensions()).forEach(root::put);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setWidth(120);
        return new Yaml(options).dump(escapeInterpolation(root));
    }

    private static Object rewriteEnvFile(Object value, Map<String, String> aliases) {
        if (value instanceof String path) return aliases.getOrDefault(path, path);
        if (value instanceof List<?> list) {
            List<Object> rewritten = new ArrayList<>(list.size());
            for (Object entry : list) rewritten.add(rewriteEnvFile(entry, aliases));
            return rewritten;
        }
        if (value instanceof Map<?, ?> map && map.get("path") instanceof String path) {
            Map<String, Object> rewritten = new LinkedHashMap<>();
            map.forEach((key, entry) -> rewritten.put(String.valueOf(key), entry));
            rewritten.put("path", aliases.getOrDefault(path, path));
            return rewritten;
        }
        return value;
    }

    private static void addNames(Map<String, Object> root, String key, List<String> names) {
        if (names.isEmpty()) return;
        Map<String, Object> resources = new LinkedHashMap<>();
        names.stream().sorted().forEach(name -> resources.put(name, null));
        root.put(key, resources);
    }

    private static void addResources(Map<String, Object> root, String key, List<String> names,
                                     Map<String, Object> definitions) {
        if (!definitions.isEmpty()) {
            root.put(key, new TreeMap<>(definitions));
        } else addNames(root, key, names);
    }

    private static Object escapeInterpolation(Object value) {
        if (value instanceof String string) return string.replace("$", "$$");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> escaped = new LinkedHashMap<>();
            map.forEach((key, nested) -> escaped.put(String.valueOf(key).replace("$", "$$"), escapeInterpolation(nested)));
            return escaped;
        }
        if (value instanceof List<?> list) {
            List<Object> escaped = new ArrayList<>(list.size());
            list.forEach(item -> escaped.add(escapeInterpolation(item)));
            return escaped;
        }
        return value;
    }
}
