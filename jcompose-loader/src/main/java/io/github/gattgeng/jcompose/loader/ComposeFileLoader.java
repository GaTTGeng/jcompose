package io.github.gattgeng.jcompose.loader;

import io.github.gattgeng.jcompose.api.ComposeProject;
import io.github.gattgeng.jcompose.api.ComposeValidationException;
import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Loads Compose YAML, expands variables, resolves service env files, and merges overlays. */
public final class ComposeFileLoader {
    private static final List<String> DEFAULT_FILES = List.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml");
    private static final Map<String, String> DEFAULT_OVERRIDES = Map.of(
            "compose.yaml", "compose.override.yaml",
            "compose.yml", "compose.override.yml",
            "docker-compose.yaml", "docker-compose.override.yaml",
            "docker-compose.yml", "docker-compose.override.yml");
    private static final Set<String> REPLACE_FIELDS = Set.of("command", "entrypoint", "test");
    private static final Set<String> KEYED_LIST_FIELDS = Set.of("environment", "labels");
    private static final Set<String> TARGETED_LIST_FIELDS = Set.of("volumes", "secrets", "configs");
    private static final Set<String> DEDUPLICATED_LIST_FIELDS = Set.of("cap_add", "cap_drop",
            "constraints", "device_cgroup_rules", "expose", "external_links", "generic_resources",
            "preferences", "security_opt");
    private static final Set<String> PORT_MAPPING_FIELDS = Set.of(
            "target", "published", "host_ip", "protocol", "mode", "name", "app_protocol");
    private static final Set<String> EXTENDS_UNIQUE_LIST_FIELDS = Set.of("cap_add", "cap_drop", "configs",
            "constraints", "device_cgroup_rules", "expose", "external_links", "generic_resources", "ports",
            "preferences", "secrets", "security_opt");

    /** Loads one Compose file. Conventional default names are discovered from the working directory upward. */
    public ComposeProject load(Path workingDirectory, String composeFileName) {
        return load(workingDirectory, composeFileName, null);
    }

    /**
     * Loads one Compose file with per-call interpolation values that take precedence over the
     * process environment and the file's adjacent {@code .env} values.
     */
    public ComposeProject load(Path workingDirectory, String composeFileName,
                               Map<String, String> environmentOverrides) {
        Path file = resolve(workingDirectory, composeFileName);
        LoadedDocument document = read(file, environmentOverrides);
        Map<String, Object> root = expandIncludes(file, document.root(), environmentOverrides,
                document.environment(), file.getParent(), new java.util.LinkedHashSet<>());
        return toProject(file, root, environmentOverrides, document.environment(), document.provenance());
    }

    /**
     * Loads a project using Docker Compose's conventional default override file when the
     * requested name is a conventional default. Custom file paths remain single-file loads.
     */
    public ComposeProject loadProject(Path workingDirectory, String composeFileName) {
        return loadProject(workingDirectory, composeFileName, null);
    }

    /** Loads a project and its conventional override, applying per-call interpolation values. */
    public ComposeProject loadProject(Path workingDirectory, String composeFileName,
                                      Map<String, String> environmentOverrides) {
        Path file = resolve(workingDirectory, composeFileName);
        String overrideName = defaultOverrideName(composeFileName, file.getFileName().toString());
        if (overrideName != null) {
            Path override = file.resolveSibling(overrideName);
            if (Files.isRegularFile(override)) {
                return loadMerged(workingDirectory, List.of(file.toString(), override.toString()), environmentOverrides);
            }
        }
        LoadedDocument document = read(file, environmentOverrides);
        Map<String, Object> root = expandIncludes(file, document.root(), environmentOverrides,
                document.environment(), file.getParent(), new java.util.LinkedHashSet<>());
        return toProject(file, root, environmentOverrides, document.environment(), document.provenance());
    }

    /** Returns whether the requested/default Compose file can be discovered from the working directory. */
    public boolean hasComposeFile(Path workingDirectory, String composeFileName) {
        return find(workingDirectory, composeFileName) != null;
    }

    /** Applies a documented incremental merge: mappings merge recursively and later scalars replace. */
    public ComposeProject loadMerged(Path workingDirectory, List<String> composeFiles) {
        return loadMerged(workingDirectory, composeFiles, null);
    }

    /**
     * Applies a documented incremental merge with per-call interpolation values that take
     * precedence over the process environment and each file's adjacent {@code .env} values.
     * The supplied values are never written to the process environment.
     */
    public ComposeProject loadMerged(Path workingDirectory, List<String> composeFiles,
                                     Map<String, String> environmentOverrides) {
        if (composeFiles == null || composeFiles.isEmpty()) throw new IllegalArgumentException("At least one Compose file is required.");
        Map<String, Object> merged = null;
        Map<String, String> environment = Map.of();
        Map<String, Path> provenance = null;
        Path base = null;
        for (String name : composeFiles) {
            Path file = resolve(workingDirectory, name);
            if (base == null) base = file;
            LoadedDocument document = read(file, environmentOverrides, base.getParent().resolve(".env"));
            environment = document.environment();
            if (merged == null) {
                merged = unwrapMergeTags(document.root());
                provenance = document.provenance();
            } else {
                Map<String, Object> baseDocument = merged;
                merged = merge(merged, document.root(), "");
                provenance = mergeProvenance(baseDocument, document.root(), merged, provenance, document.provenance());
            }
        }
        Map<String, Object> root = expandIncludes(base, merged, environmentOverrides,
                environment, base.getParent(), new java.util.LinkedHashSet<>());
        return toProject(base, root, environmentOverrides, environment, provenance);
    }

    private static Map<String, Object> expandIncludes(Path source, Map<String, Object> root,
                                                       Map<String, String> environmentOverrides,
                                                       Map<String, String> environment,
                                                       Path baseDirectory, Set<Path> including) {
        Object configured = root.get("include");
        if (configured == null) return root;
        List<?> entries = configured instanceof List<?> list ? list : List.of(configured);
        Map<String, Object> expanded = new LinkedHashMap<>(root);
        expanded.remove("include");
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!including.add(normalizedSource))
            throw new ComposeValidationException(source, "include", "circular include detected.");
        try {
            for (int index = 0; index < entries.size(); index++) {
                Object item = entries.get(index);
                List<String> paths;
                Path projectDirectory = null;
                Map<String, String> includeEnvironment = new LinkedHashMap<>(environment);
                String property = "include." + index;
                if (item instanceof String simplePath) {
                    paths = List.of(simplePath);
                } else if (item instanceof Map<?, ?> map) {
                    Map<String, Object> entry = stringKeyMapUnchecked(map);
                    for (String key : entry.keySet()) {
                        if (!Set.of("path", "project_directory", "env_file").contains(key))
                            throw new ComposeValidationException(source, property + "." + key,
                                    "unsupported include option '" + key + "'.");
                    }
                    Object rawEnvFile = entry.get("env_file");
                    if (rawEnvFile != null) {
                        List<?> envFiles = rawEnvFile instanceof List<?> list ? list : List.of(rawEnvFile);
                        Map<String, String> includeEnvFile = new LinkedHashMap<>();
                        for (Object envFile : envFiles) {
                            if (!(envFile instanceof String envPath) || envPath.isBlank())
                                throw new ComposeValidationException(source, property + ".env_file",
                                        "env_file must be a path or list of paths.");
                            try {
                                includeEnvFile.putAll(parseEnvFile(source.getParent().resolve(envPath).normalize(),
                                        environmentWithOverrides(environmentOverrides)));
                            } catch (IOException exception) {
                                throw new ComposeValidationException(source, property + ".env_file",
                                        "could not load include environment file '" + envPath + "': " + exception.getMessage());
                            }
                        }
                        includeEnvFile.putAll(environment);
                        includeEnvironment = includeEnvFile;
                    }
                    Object rawPath = entry.get("path");
                    if (rawPath instanceof String value) paths = List.of(value);
                    else if (rawPath instanceof List<?> pathList && !pathList.isEmpty()
                            && pathList.stream().allMatch(value -> value instanceof String path && !path.isBlank())) {
                        paths = pathList.stream().map(String.class::cast).toList();
                    } else throw new ComposeValidationException(source, property + ".path",
                            "include path must be a non-empty string or non-empty list of strings.");
                    Object rawDirectory = entry.get("project_directory");
                    if (rawDirectory != null && (!(rawDirectory instanceof String directoryValue) || directoryValue.isBlank()))
                        throw new ComposeValidationException(source, property + ".project_directory",
                                "project_directory must be a non-empty path.");
                    projectDirectory = rawDirectory == null ? null
                            : source.getParent().resolve((String) rawDirectory).normalize();
                } else throw new ComposeValidationException(source, property,
                        "include entries must be paths or mappings.");
                Map<String, Object> options = projectDirectory == null ? Map.of()
                        : Map.of("project_directory", projectDirectory.toString());
                importIncludedFiles(source, paths, options,
                        includeEnvironment, expanded, baseDirectory, including, property);
            }
        } finally {
            including.remove(normalizedSource);
        }
        return expanded;
    }

    private static void importIncludedFiles(Path source, List<String> paths, Map<String, Object> options,
                                            Map<String, String> includeEnvironment,
                                            Map<String, Object> expanded,
                                            Path baseDirectory, Set<Path> including, String property) {
        List<Path> includedFiles = new ArrayList<>();
        for (String path : paths) {
            if (path.isBlank())
                throw new ComposeValidationException(source, property + ".path", "include path must not be empty.");
            if (path.contains("://") || path.startsWith("git@") || path.startsWith("oci://"))
                throw new ComposeValidationException(source, property + ".path",
                        "remote include sources are not supported; use a local Compose file path.");
            Path includedFile = source.getParent().resolve(path).normalize().toAbsolutePath();
            if (!Files.isRegularFile(includedFile))
                throw new ComposeValidationException(source, property + ".path",
                        "included Compose file not found: " + includedFile);
            includedFiles.add(includedFile);
        }
        Path firstFile = includedFiles.getFirst();
        Path projectDirectory = Path.of(String.valueOf(options.getOrDefault("project_directory",
                firstFile.getParent().toString()))).toAbsolutePath().normalize();
        Map<String, Object> childRoot = null;
        Map<String, String> childEnvironment = includeEnvironment;
        for (Path includedFile : includedFiles) {
            LoadedDocument document = read(includedFile, includeEnvironment, projectDirectory.resolve(".env"));
            if (childRoot == null) {
                childRoot = unwrapMergeTags(document.root());
                childEnvironment = document.environment();
            } else childRoot = merge(childRoot, document.root(), "");
        }
        Map<String, Object> child = expandIncludes(firstFile, childRoot, includeEnvironment,
                childEnvironment, projectDirectory, including);
        rebaseIncludedPaths(child, projectDirectory, baseDirectory);
        for (String section : List.of("services", "networks", "volumes", "secrets", "configs")) {
            Object childValue = child.get(section);
            if (!(childValue instanceof Map<?, ?> childMap)) continue;
            Map<String, Object> destination = stringKeyMapUnchecked(
                    expanded.get(section) instanceof Map<?, ?> existing ? existing : Map.of());
            for (var entry : childMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (destination.containsKey(name))
                    throw new ComposeValidationException(source, property + ".path",
                            "included " + section + " resource '" + name + "' conflicts with an existing definition.");
                destination.put(name, entry.getValue());
            }
            expanded.put(section, destination);
        }
    }

    private static void rebaseIncludedPaths(Map<String, Object> root, Path sourceDirectory, Path baseDirectory) {
        Object services = root.get("services");
        if (services instanceof Map<?, ?> map) {
            Map<String, Object> definitions = stringKeyMapUnchecked(map);
            for (var entry : definitions.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> service) {
                    Map<String, Object> rebased = stringKeyMapUnchecked(service);
                    rebaseServicePaths(rebased, sourceDirectory, baseDirectory);
                    definitions.put(entry.getKey(), rebased);
                }
            }
            root.put("services", definitions);
        }
        for (String section : List.of("configs", "secrets")) {
            Object resources = root.get(section);
            if (resources instanceof Map<?, ?> map) {
                Map<String, Object> definitions = stringKeyMapUnchecked(map);
                for (var entry : definitions.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> resource) {
                        Map<String, Object> definition = stringKeyMapUnchecked(resource);
                        if (definition.get("file") instanceof String path)
                            definition.put("file", rebasePath(path, sourceDirectory, baseDirectory));
                        definitions.put(entry.getKey(), definition);
                    }
                }
                root.put(section, definitions);
            }
        }
    }

    private static Path resolve(Path directory, String requested) {
        Path file = find(directory, requested);
        if (file != null) return file;
        Path root = directory.toAbsolutePath().normalize();
        throw new IllegalArgumentException("Compose file not found from " + root + ": " + requested);
    }

    private static String defaultOverrideName(String requested, String resolvedName) {
        if (requested == null || requested.isBlank()) return DEFAULT_OVERRIDES.get(resolvedName);
        Path requestedPath = Path.of(requested);
        Path requestedName = requestedPath.getFileName();
        if (requestedPath.isAbsolute() || requestedPath.getNameCount() != 1 || requestedName == null
                || !DEFAULT_FILES.contains(requestedName.toString())) return null;
        return DEFAULT_OVERRIDES.get(resolvedName);
    }

    private static Path find(Path directory, String requested) {
        Path root = directory.toAbsolutePath().normalize();
        if (requested != null && !requested.isBlank()) {
            Path candidate = root.resolve(requested).normalize();
            if (Files.isRegularFile(candidate)) return candidate;
            // A custom -f path is explicit; only the conventional defaults participate in discovery.
            Path requestedPath = Path.of(requested);
            Path requestedName = requestedPath.getFileName();
            if (requestedPath.isAbsolute() || requestedPath.getNameCount() != 1
                    || requestedName == null || !DEFAULT_FILES.contains(requestedName.toString())) return null;
        }
        for (Path current = root; current != null; current = current.getParent()) {
            for (String name : DEFAULT_FILES) {
                Path candidate = current.resolve(name);
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static LoadedDocument read(Path file, Map<String, String> environmentOverrides) {
        return read(file, environmentOverrides, file.getParent().resolve(".env"));
    }

    @SuppressWarnings("unchecked")
    private static LoadedDocument read(Path file, Map<String, String> environmentOverrides, Path dotenvPath) {
        try {
            String text = Files.readString(file);
            Map<String, String> process = environmentWithOverrides(environmentOverrides);
            Map<String, String> envFile = parseEnvFile(dotenvPath, process);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new ComposeSafeConstructor(options));
            Node document = yaml.compose(new StringReader(text));
            if (document == null) throw new ComposeValidationException(file, "$", "the document must be a YAML mapping.");
            Node expanded = interpolateValues(document, envFile, process);
            StringWriter serialized = new StringWriter();
            yaml.serialize(expanded, serialized);
            Object parsed = yaml.load(serialized.toString());
            if (!(parsed instanceof Map<?, ?> raw)) throw new ComposeValidationException(file, "$", "the document must be a YAML mapping.");
            Map<String, String> environment = new LinkedHashMap<>(envFile);
            environment.putAll(process);
            environment.values().removeIf(java.util.Objects::isNull);
            Map<String, Object> root = stringKeyMap(raw, file, "$");
            return new LoadedDocument(root, environment, buildProvenance(root, file));
        } catch (ComposeValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ComposeValidationException(file, "$", null, "could not load the Compose document: " + exception.getMessage(), null, null, exception);
        }
    }

    private static Node interpolateValues(Node node, Map<String, String> dotenv, Map<String, String> process) {
        if (node instanceof MappingNode mapping) {
            List<NodeTuple> entries = new ArrayList<>();
            for (var entry : mapping.getValue())
                entries.add(new NodeTuple(entry.getKeyNode(), interpolateValues(entry.getValueNode(), dotenv, process)));
            mapping.setValue(entries);
            return mapping;
        }
        if (node instanceof SequenceNode sequence) {
            List<Node> values = sequence.getValue();
            for (int index = 0; index < values.size(); index++)
                values.set(index, interpolateValues(values.get(index), dotenv, process));
            return sequence;
        }
        if (node instanceof ScalarNode scalar
                && (scalar.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN
                || scalar.getScalarStyle() == DumperOptions.ScalarStyle.DOUBLE_QUOTED)) {
            return new ScalarNode(scalar.getTag(), interpolate(scalar.getValue(), dotenv, process),
                    scalar.getStartMark(), scalar.getEndMark(), scalar.getScalarStyle());
        }
        return node;
    }

    private static ComposeProject toProject(Path file, Map<String, Object> root,
                                            Map<String, String> environmentOverrides,
                                            Map<String, String> environment,
                                            Map<String, Path> provenance) {
        try {
            return toProjectValidated(file, root, environmentOverrides, environment);
        } catch (ComposeValidationException exception) {
            if (!file.equals(exception.sourceFile())) throw exception;
            Path source = sourceFor(file, exception.propertyPath(), exception.serviceName(), provenance);
            if (source.equals(exception.sourceFile())) throw exception;
            String prefix = "Invalid Compose input in '" + exception.sourceFile() + "'"
                    + (exception.propertyPath() == null ? "" : " at '" + exception.propertyPath() + "'")
                    + (exception.serviceName() == null ? "" : ", service '" + exception.serviceName() + "'")
                    + (exception.line() == null ? "" : " (" + exception.line() + ":" + exception.column() + ")")
                    + ": ";
            String message = exception.getMessage();
            String detail = message.startsWith(prefix) ? message.substring(prefix.length()) : message;
            throw new ComposeValidationException(source, exception.propertyPath(), exception.serviceName(),
                    detail, exception.line(), exception.column(), exception.getCause());
        }
    }

    private static ComposeProject toProjectValidated(Path file, Map<String, Object> root,
                                                       Map<String, String> environmentOverrides,
                                                       Map<String, String> environment) {
        root = unwrapMergeTags(root);
        Object rawServices = root.get("services");
        if (!(rawServices instanceof Map<?, ?> serviceMap) || serviceMap.isEmpty())
            throw new ComposeValidationException(file, "services", "the document must define at least one service.");
        Map<String, Map<String, Object>> resolvedServices = new LinkedHashMap<>();
        for (var entry : serviceMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!name.matches("[A-Za-z0-9_.-]+"))
                throw new ComposeValidationException(file, "services." + name, name,
                        "service name contains invalid characters.", null, null, null);
            if (!(entry.getValue() instanceof Map<?, ?> config))
                throw new ComposeValidationException(file, "services." + name, name, "service configuration must be a mapping.", null, null, null);
            resolvedServices.put(name, resolveExtendedService(file, name, stringKeyMap(config, file, "services." + name),
                    root, environmentOverrides, file.getParent(), new java.util.LinkedHashSet<>()));
        }
        Map<String, ServiceDefinition> services = new LinkedHashMap<>();
        for (var entry : resolvedServices.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> service = entry.getValue();
            validateServiceProfiles(file, name, service);
            validateServiceFields(file, name, service);
            if (service.containsKey("attach") && !(service.get("attach") instanceof Boolean))
                throw new ComposeValidationException(file, "services." + name + ".attach", name,
                        "attach must be a boolean.", null, null, null);
            if (service.containsKey("network_mode") && service.containsKey("networks"))
                throw new ComposeValidationException(file, "services." + name + ".networks", name,
                        "service cannot combine network_mode with networks.", null, null, null);
            if (service.containsKey("depends_on"))
                validateServiceDependencies(file, name, service.get("depends_on"), serviceMap);
            if (service.containsKey("links"))
                validateServiceLinks(file, name, service.get("links"), serviceMap);
            validateServiceNetworks(file, name, service.get("networks"), root.get("networks"));
            validateServiceResources(file, name, serviceVolumeReferences(service.get("volumes")),
                    root.get("volumes"), "volumes");
            validateServiceResources(file, name, service.get("secrets"), root.get("secrets"), "secrets");
            validateServiceResources(file, name, service.get("configs"), root.get("configs"), "configs");
            resolveEnvFiles(file.getParent(), service, file, name, environmentOverrides);
            services.put(name, new ServiceDefinition(name, service));
        }
        return new ComposeProject(file, services, names(root.get("volumes")), names(root.get("networks")),
                names(root.get("secrets")), names(root.get("configs")), definitions(root.get("volumes")),
                definitions(root.get("networks")), definitions(root.get("secrets")),
                definitions(root.get("configs")), extensions(root), environment);
    }

    private static Map<String, Object> resolveExtendedService(Path source, String name, Map<String, Object> service,
                                                               Map<String, Object> root,
                                                               Map<String, String> environmentOverrides,
                                                               Path baseDirectory, Set<String> resolving) {
        Object extendsValue = service.get("extends");
        if (extendsValue == null) {
            Map<String, Object> resolved = new LinkedHashMap<>(service);
            if (!source.toAbsolutePath().normalize().getParent().equals(baseDirectory.toAbsolutePath().normalize()))
                rebaseServicePaths(resolved, source.getParent(), baseDirectory);
            return resolved;
        }
        if (!(extendsValue instanceof Map<?, ?> extendsMap))
            throw new ComposeValidationException(source, "services." + name + ".extends", name,
                    "extends must be a mapping with a service name and an optional file.", null, null, null);
        Object serviceValue = extendsMap.get("service");
        if (!(serviceValue instanceof String baseName) || baseName.isBlank())
            throw new ComposeValidationException(source, "services." + name + ".extends.service", name,
                    "extends must define a non-empty service name.", null, null, null);
        Object fileValue = extendsMap.get("file");
        if (fileValue != null && (!(fileValue instanceof String path) || path.isBlank()))
            throw new ComposeValidationException(source, "services." + name + ".extends.file", name,
                    "extends file must be a non-empty path.", null, null, null);

        String key = source.toAbsolutePath().normalize() + "#" + name;
        if (!resolving.add(key))
            throw new ComposeValidationException(source, "services." + name + ".extends", name,
                    "circular service extends reference detected.", null, null, null);
        try {
            Path referencedFile = fileValue == null ? source
                    : baseDirectory.resolve((String) fileValue).normalize().toAbsolutePath();
            Map<String, Object> referencedRoot = root;
            if (!referencedFile.equals(source.toAbsolutePath().normalize())) {
                if (!Files.isRegularFile(referencedFile))
                    throw new ComposeValidationException(source, "services." + name + ".extends.file", name,
                            "referenced Compose file not found: " + referencedFile, null, null, null);
                referencedRoot = unwrapMergeTags(read(referencedFile, environmentOverrides).root());
            }
            Object referencedServices = referencedRoot.get("services");
            if (!(referencedServices instanceof Map<?, ?> services)
                    || !(services.get(baseName) instanceof Map<?, ?> referencedService))
                throw new ComposeValidationException(source, "services." + name + ".extends.service", name,
                        "referenced service '" + baseName + "' was not found in '" + referencedFile + "'.", null, null, null);
            Map<String, Object> inherited = resolveExtendedService(referencedFile, baseName,
                    stringKeyMap(referencedService, referencedFile, "services." + baseName), referencedRoot,
                    environmentOverrides, baseDirectory, resolving);
            Map<String, Object> overrides = new LinkedHashMap<>(service);
            overrides.remove("extends");
            if (!source.toAbsolutePath().normalize().getParent().equals(baseDirectory.toAbsolutePath().normalize()))
                rebaseServicePaths(overrides, source.getParent(), baseDirectory);
            validateExtendedHealthcheck(source, name, inherited, overrides);
            return mergeExtended(inherited, overrides);
        } finally {
            resolving.remove(key);
        }
    }

    private static void validateExtendedHealthcheck(Path source, String service,
                                                    Map<String, Object> inherited,
                                                    Map<String, Object> overrides) {
        Object healthcheck = overrides.get("healthcheck");
        if (!(healthcheck instanceof Map<?, ?> child) || !Boolean.TRUE.equals(child.get("disable"))) return;
        Object inheritedHealthcheck = inherited.get("healthcheck");
        if (inheritedHealthcheck instanceof Map<?, ?> parent && Boolean.TRUE.equals(parent.get("disable"))) return;
        throw new ComposeValidationException(source, "services." + service + ".healthcheck.disable", service,
                "healthcheck.disable cannot be enabled by an extending service unless it is enabled in the base service.",
                null, null, null);
    }

    private static void rebaseServicePaths(Map<String, Object> service, Path sourceDirectory, Path baseDirectory) {
        Object extendsValue = service.get("extends");
        if (extendsValue instanceof Map<?, ?> map && map.get("file") instanceof String path) {
            Map<String, Object> rebased = stringKeyMapUnchecked(map);
            rebased.put("file", rebasePath(path, sourceDirectory, baseDirectory));
            service.put("extends", rebased);
        }
        Object build = service.get("build");
        if (build instanceof String context) service.put("build", rebasePath(context, sourceDirectory, baseDirectory));
        else if (build instanceof Map<?, ?> map) {
            Map<String, Object> rebased = stringKeyMapUnchecked(map);
            if (rebased.get("context") instanceof String context)
                rebased.put("context", rebasePath(context, sourceDirectory, baseDirectory));
            service.put("build", rebased);
        }
        Object envFile = service.get("env_file");
        if (envFile instanceof String path) service.put("env_file", rebasePath(path, sourceDirectory, baseDirectory));
        else if (envFile instanceof List<?> paths) {
            List<Object> rebased = new ArrayList<>();
            for (Object item : paths) {
                if (item instanceof String path) rebased.add(rebasePath(path, sourceDirectory, baseDirectory));
                else if (item instanceof Map<?, ?> map) {
                    Map<String, Object> entry = stringKeyMapUnchecked(map);
                    if (entry.get("path") instanceof String path)
                        entry.put("path", rebasePath(path, sourceDirectory, baseDirectory));
                    rebased.add(entry);
                } else rebased.add(item);
            }
            service.put("env_file", rebased);
        }
        Object volumes = service.get("volumes");
        if (volumes instanceof List<?> list) {
            List<Object> rebased = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String value) rebased.add(rebaseVolumePath(value, sourceDirectory, baseDirectory));
                else if (item instanceof Map<?, ?> map) {
                    Map<String, Object> entry = stringKeyMapUnchecked(map);
                    if ("bind".equals(entry.get("type")) && entry.get("source") instanceof String path)
                        entry.put("source", rebasePath(path, sourceDirectory, baseDirectory));
                    rebased.add(entry);
                } else rebased.add(item);
            }
            service.put("volumes", rebased);
        }
    }

    private static String rebasePath(String path, Path sourceDirectory, Path baseDirectory) {
        Path value = Path.of(path);
        if (value.isAbsolute() || path.startsWith("~") || path.startsWith("git@")
                || path.contains("://") || path.startsWith("-")) return path;
        Path absolute = sourceDirectory.resolve(value).normalize();
        try { return baseDirectory.relativize(absolute).toString().replace('\\', '/'); }
        catch (IllegalArgumentException exception) { return absolute.toString(); }
    }

    private static String rebaseVolumePath(String volume, Path sourceDirectory, Path baseDirectory) {
        String[] parts = volume.split(":", 3);
        if (parts.length < 2 || !(parts[0].startsWith(".") || parts[0].startsWith("/")
                || parts[0].startsWith("~") || parts[0].matches("^[A-Za-z]:[\\\\/].*"))) return volume;
        String source = rebasePath(parts[0], sourceDirectory, baseDirectory);
        if (!source.startsWith("./") && !source.startsWith("../") && !Path.of(source).isAbsolute())
            source = "./" + source;
        return source + ":" + parts[1]
                + (parts.length == 3 ? ":" + parts[2] : "");
    }

    private static Map<String, Object> mergeExtended(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (var entry : overrides.entrySet()) {
            Object before = result.get(entry.getKey()), after = entry.getValue();
            if (before instanceof Map<?, ?> left && after instanceof Map<?, ?> right)
                result.put(entry.getKey(), mergeExtended(stringKeyMapUnchecked(left), stringKeyMapUnchecked(right)));
            else if (before instanceof List<?> left && after instanceof List<?> right) {
                String key = entry.getKey();
                if ("volumes".equals(key) || "devices".equals(key))
                    result.put(entry.getKey(), mergeTargetedList(left, right));
                else if (key.startsWith("device_") && (key.endsWith("_bps") || key.endsWith("_iops")))
                    result.put(entry.getKey(), mergePathList(left, right));
                else if (EXTENDS_UNIQUE_LIST_FIELDS.contains(key))
                    result.put(entry.getKey(), mergeUniqueList(left, right));
                else result.put(entry.getKey(), appendAll(left, right));
            } else result.put(entry.getKey(), after);
        }
        return result;
    }

    private static List<Object> mergePathList(List<?> base, List<?> overrides) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Object value : base) entries.put(pathKey(value), value);
        for (Object value : overrides) entries.put(pathKey(value), value);
        return List.copyOf(entries.values());
    }

    private static String pathKey(Object value) {
        if (value instanceof Map<?, ?> map && map.get("path") != null) return String.valueOf(map.get("path"));
        return String.valueOf(value);
    }

    private record LoadedDocument(Map<String, Object> root, Map<String, String> environment,
                                  Map<String, Path> provenance) { }

    private static Map<String, Object> definitions(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, definition) -> result.put(String.valueOf(key), definition));
        return result;
    }

    private static void validateServiceNetworks(Path file, String service, Object configured, Object declared) {
        List<String> references;
        if (configured instanceof Map<?, ?> map) references = map.keySet().stream().map(String::valueOf).toList();
        else if (configured instanceof List<?> list) references = list.stream().map(String::valueOf).toList();
        else return;
        Set<String> known = new java.util.LinkedHashSet<>(names(declared));
        known.add("default");
        for (String network : references) {
            if (!known.contains(network))
                throw new ComposeValidationException(file, "services." + service + ".networks." + network, service,
                        "service refers to undefined network '" + network + "'.", null, null, null);
        }
    }

    private static void validateServiceProfiles(Path file, String service, Map<String, Object> configuration) {
        if (!configuration.containsKey("profiles")) return;
        Object configured = configuration.get("profiles");
        if (!(configured instanceof List<?> profiles))
            throw new ComposeValidationException(file, "services." + service + ".profiles", service,
                    "profiles must be an array of strings.", null, null, null);
        for (int index = 0; index < profiles.size(); index++) {
            if (!(profiles.get(index) instanceof String))
                throw new ComposeValidationException(file, "services." + service + ".profiles." + index, service,
                        "profile names must be strings.", null, null, null);
        }
    }

    private static void validateServiceFields(Path file, String service, Map<String, Object> configuration) {
        String path = "services." + service;
        Object image = configuration.get("image");
        if (image != null && !isScalar(image))
            throw shapeFailure(file, service, path + ".image", "a scalar", image);
        validateScalarOrMap(file, service, configuration, "build", path + ".build");
        validateListOrMap(file, service, configuration, "environment", path + ".environment");
        validatePorts(file, service, configuration.get("ports"), path + ".ports");
        for (String key : List.of("deploy", "healthcheck", "logging"))
            validateOptionalMap(file, service, configuration, key, path + "." + key);
        validateDevelop(file, service, configuration.get("develop"), path + ".develop");
        validateScalarOrMap(file, service, configuration, "extends", path + ".extends");

        Object deployValue = configuration.get("deploy");
        if (deployValue instanceof Map<?, ?> deploy) {
            validateInteger(file, service, deploy, "replicas", path + ".deploy.replicas");
            validateOptionalMap(file, service, deploy, "resources", path + ".deploy.resources");
            validateOptionalMap(file, service, deploy, "restart_policy", path + ".deploy.restart_policy");
            validateOptionalMap(file, service, deploy, "update_config", path + ".deploy.update_config");
            validateOptionalMap(file, service, deploy, "rollback_config", path + ".deploy.rollback_config");
            validateNestedBytes(file, service, deploy, "resources", "limits", "memory",
                    path + ".deploy.resources.limits.memory");
            validateNestedBytes(file, service, deploy, "resources", "reservations", "memory",
                    path + ".deploy.resources.reservations.memory");
            validateNestedInteger(file, service, deploy, "restart_policy", "max_retries",
                    path + ".deploy.restart_policy.max_retries");
            validateNestedDurations(file, service, deploy, "restart_policy", path + ".deploy.restart_policy",
                    "delay", "window", "period");
            for (String block : List.of("update_config", "rollback_config")) {
                validateNestedDurations(file, service, deploy, block, path + ".deploy." + block, "delay", "monitor");
                validateNestedNumeric(file, service, deploy, block, "max_failure_ratio",
                        path + ".deploy." + block + ".max_failure_ratio");
            }
        }

        Object healthcheckValue = configuration.get("healthcheck");
        if (healthcheckValue instanceof Map<?, ?> healthcheck) {
            validateBoolean(file, service, healthcheck, "disable", path + ".healthcheck.disable");
            validateInteger(file, service, healthcheck, "retries", path + ".healthcheck.retries");
            validateDurations(file, service, healthcheck, path + ".healthcheck",
                    "interval", "timeout", "start_period", "start_interval");
        }

        validateDurations(file, service, configuration, path, "stop_grace_period");
        for (String key : List.of("shm_size", "mem_limit", "memswap_limit", "mem_reservation"))
            validateBytes(file, service, configuration, key, path + "." + key);
        for (String key : List.of("privileged", "tty", "stdin_open", "read_only", "init", "oom_kill_disable"))
            validateBoolean(file, service, configuration, key, path + "." + key);

        Object buildValue = configuration.get("build");
        if (buildValue instanceof Map<?, ?> build) {
            for (String key : List.of("privileged", "pull", "no_cache"))
                validateBoolean(file, service, build, key, path + ".build." + key);
        }
    }

    private static void validateDevelop(Path file, String service, Object configured, String path) {
        if (configured == null) return;
        if (!(configured instanceof Map<?, ?> develop))
            throw shapeFailure(file, service, path, "a YAML mapping", configured);
        for (Object key : develop.keySet()) {
            if (!(key instanceof String field) || !field.equals("watch"))
                throw new ComposeValidationException(file, path + "." + key, service,
                        "unsupported develop option '" + key + "'.", null, null, null);
        }
        Object watchValue = develop.get("watch");
        if (watchValue == null) return;
        if (!(watchValue instanceof List<?> rules))
            throw shapeFailure(file, service, path + ".watch", "a YAML list", watchValue);
        for (int index = 0; index < rules.size(); index++)
            validateDevelopWatchRule(file, service, rules.get(index), path + ".watch[" + index + "]");
    }

    private static void validateDevelopWatchRule(Path file, String service, Object configured, String path) {
        if (!(configured instanceof Map<?, ?> rule))
            throw shapeFailure(file, service, path, "a YAML mapping", configured);
        Set<String> fields = Set.of("path", "action", "target", "ignore", "include", "initial_sync", "exec");
        for (Object key : rule.keySet()) {
            if (!(key instanceof String field) || !fields.contains(field))
                throw new ComposeValidationException(file, path + "." + key, service,
                        "unsupported develop watch option '" + key + "'.", null, null, null);
        }

        String rulePath = path + ".path";
        Object source = rule.get("path");
        if (!isNonBlankString(source) || isAbsoluteWatchPath((String) source))
            throw new ComposeValidationException(file, rulePath, service,
                    "a non-empty relative source path is required.", null, null, null);
        String actionPath = path + ".action";
        Object actionValue = rule.get("action");
        if (!isNonBlankString(actionValue))
            throw new ComposeValidationException(file, actionPath, service,
                    "a watch action is required.", null, null, null);
        String action = String.valueOf(actionValue);
        if (!Set.of("rebuild", "restart", "sync", "sync+restart", "sync+exec").contains(action))
            throw new ComposeValidationException(file, actionPath, service,
                    "watch action must be rebuild, restart, sync, sync+restart, or sync+exec.", null, null, null);

        boolean sync = action.startsWith("sync");
        Object target = rule.get("target");
        if (sync && (!isNonBlankString(target) || !String.valueOf(target).startsWith("/")))
            throw new ComposeValidationException(file, path + ".target", service,
                    "an absolute container target is required for sync actions.", null, null, null);
        if (!sync && target != null)
            throw new ComposeValidationException(file, path + ".target", service,
                    "target is only supported for sync actions.", null, null, null);

        validateStringList(file, service, rule.get("ignore"), path + ".ignore");
        validateStringOrStringList(file, service, rule.get("include"), path + ".include");
        validateBoolean(file, service, rule, "initial_sync", path + ".initial_sync");
        if (rule.containsKey("initial_sync") && !sync)
            throw new ComposeValidationException(file, path + ".initial_sync", service,
                    "initial_sync is only supported for sync actions.", null, null, null);

        Object exec = rule.get("exec");
        if ("sync+exec".equals(action)) validateDevelopWatchExec(file, service, exec, path + ".exec");
        else if (exec != null)
            throw new ComposeValidationException(file, path + ".exec", service,
                    "exec is only supported for the sync+exec action.", null, null, null);
    }

    private static void validateDevelopWatchExec(Path file, String service, Object configured, String path) {
        if (!(configured instanceof Map<?, ?> exec))
            throw shapeFailure(file, service, path, "a YAML mapping", configured);
        Set<String> fields = Set.of("command", "user", "privileged", "working_dir", "environment");
        for (Object key : exec.keySet()) {
            if (!(key instanceof String field) || !fields.contains(field))
                throw new ComposeValidationException(file, path + "." + key, service,
                        "unsupported watch exec option '" + key + "'.", null, null, null);
        }
        Object command = exec.get("command");
        if (!isNonBlankString(command) && !(command instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(String.class::isInstance)))
            throw new ComposeValidationException(file, path + ".command", service,
                    "a non-empty shell or exec command is required.", null, null, null);
        for (String field : List.of("user", "working_dir")) {
            Object value = exec.get(field);
            if (value != null && !(value instanceof String))
                throw shapeFailure(file, service, path + "." + field, "a scalar", value);
        }
        validateBoolean(file, service, exec, "privileged", path + ".privileged");
        validateListOrMap(file, service, exec, "environment", path + ".environment");
    }

    private static void validateStringOrStringList(Path file, String service, Object value, String path) {
        if (value == null) return;
        if (value instanceof String) return;
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) return;
        throw shapeFailure(file, service, path, "a string or an array of strings", value);
    }

    private static boolean isNonBlankString(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static boolean isAbsoluteWatchPath(String value) {
        try {
            return Path.of(value).isAbsolute() || value.matches("^[A-Za-z]:[/\\\\].*")
                    || value.startsWith("\\\\");
        } catch (java.nio.file.InvalidPathException exception) {
            return true;
        }
    }

    private static void validateStringList(Path file, String service, Object value, String path) {
        if (value == null) return;
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) return;
        throw shapeFailure(file, service, path, "an array of strings", value);
    }

    private static void validateOptionalMap(Path file, String service, Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value != null && !(value instanceof Map<?, ?>))
            throw shapeFailure(file, service, path, "a YAML mapping", value);
    }

    private static void validateScalarOrMap(Path file, String service, Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value != null && value instanceof List<?>)
            throw shapeFailure(file, service, path, "a scalar or YAML mapping", value);
    }

    private static void validateListOrMap(Path file, String service, Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value != null && !(value instanceof List<?> || value instanceof Map<?, ?>))
            throw shapeFailure(file, service, path, "a YAML list or mapping", value);
    }

    private static void validatePorts(Path file, String service, Object value, String path) {
        if (value == null) return;
        if (!(value instanceof List<?> ports))
            throw shapeFailure(file, service, path, "a YAML list", value);
        for (int index = 0; index < ports.size(); index++) {
            Object port = ports.get(index);
            String itemPath = path + "[" + index + "]";
            if (port instanceof Map<?, ?> mapping) {
                validateLongPortMapping(file, service, mapping, itemPath);
                continue;
            }
            if (!isScalar(port))
                throw shapeFailure(file, service, itemPath, "a scalar or YAML mapping", port);
            String text = String.valueOf(port);
            if (!isValidPortMapping(text))
                throw conversionFailure(file, service, itemPath, port, "a supported short port mapping");
        }
    }

    private static void validateLongPortMapping(Path file, String service, Map<?, ?> mapping, String path) {
        for (Object key : mapping.keySet()) {
            if (!(key instanceof String field) || !PORT_MAPPING_FIELDS.contains(field))
                throw new ComposeValidationException(file, path + "." + key, service,
                        "unsupported long port option '" + key + "'.", null, null, null);
        }

        Object target = mapping.get("target");
        String targetPath = path + ".target";
        if (target == null)
            throw new ComposeValidationException(file, targetPath, service,
                    "a container port or port range is required.", null, null, null);
        if (!isScalar(target) || !isPortRange(String.valueOf(target), false))
            throw conversionFailure(file, service, targetPath, target, "a container port or port range");

        Object published = mapping.get("published");
        String publishedPath = path + ".published";
        if (published != null && (!isScalar(published)
                || !String.valueOf(published).isBlank() && !isPortRange(String.valueOf(published), true)))
            throw conversionFailure(file, service, publishedPath, published, "a published port or port range");

        Object hostIp = mapping.get("host_ip");
        String hostIpPath = path + ".host_ip";
        if (hostIp != null && (!isScalar(hostIp) || !isIpAddress(String.valueOf(hostIp))))
            throw conversionFailure(file, service, hostIpPath, hostIp, "an IPv4 or IPv6 address");

        Object protocol = mapping.get("protocol");
        String protocolPath = path + ".protocol";
        if (protocol != null && (!isScalar(protocol)
                || !("tcp".equals(protocol) || "udp".equals(protocol))))
            throw conversionFailure(file, service, protocolPath, protocol, "tcp or udp");

        Object mode = mapping.get("mode");
        String modePath = path + ".mode";
        if (mode != null && (!isScalar(mode)
                || !("host".equals(mode) || "ingress".equals(mode))))
            throw conversionFailure(file, service, modePath, mode, "host or ingress");

        for (String field : List.of("name", "app_protocol")) {
            Object value = mapping.get(field);
            if (value != null && !isScalar(value))
                throw shapeFailure(file, service, path + "." + field, "a scalar", value);
        }
    }

    private static boolean isIpAddress(String value) {
        String host = value.trim();
        return isIpv4Address(host) || isIpv6Address(host);
    }

    private static boolean isValidPortMapping(String value) {
        String text = value.trim();
        int slash = text.lastIndexOf('/');
        if (slash >= 0) {
            String protocol = text.substring(slash + 1);
            if (!("tcp".equals(protocol) || "udp".equals(protocol))) return false;
            text = text.substring(0, slash);
        }

        if (text.startsWith("[")) {
            int closingBracket = text.indexOf(']');
            if (closingBracket <= 1 || closingBracket == text.length() - 1 || text.charAt(closingBracket + 1) != ':')
                return false;
            String address = text.substring(1, closingBracket);
            if (!isIpv6Address(address)) return false;
            String[] parts = text.substring(closingBracket + 2).split(":", -1);
            return parts.length == 2 && isPortRange(parts[0], true) && isPortRange(parts[1], false);
        }

        String[] parts = text.split(":", -1);
        if (parts.length > 3) {
            int containerSeparator = text.lastIndexOf(':');
            int publishedSeparator = text.lastIndexOf(':', containerSeparator - 1);
            return publishedSeparator > 0 && isIpv6Address(text.substring(0, publishedSeparator))
                    && isPortRange(text.substring(publishedSeparator + 1, containerSeparator), true)
                    && isPortRange(text.substring(containerSeparator + 1), false);
        }
        return switch (parts.length) {
            case 1 -> isPortRange(parts[0], false);
            case 2 -> isPortRange(parts[0], true) && isPortRange(parts[1], false);
            case 3 -> isIpv4Address(parts[0]) && isPortRange(parts[1], true) && isPortRange(parts[2], false);
            default -> false;
        };
    }

    private static boolean isPortRange(String value, boolean allowZero) {
        String[] range = value.split("-", -1);
        if (range.length > 2 || !range[0].matches("[0-9]+")
                || (range.length == 2 && !range[1].matches("[0-9]+"))) return false;
        int minimum = allowZero ? 0 : 1;
        try {
            int start = Integer.parseInt(range[0]);
            if (start < minimum || start > 65535) return false;
            if (range.length == 1) return true;
            int end = Integer.parseInt(range[1]);
            return end >= start && end <= 65535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isIpv4Address(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        try {
            for (String octet : octets) {
                int number = Integer.parseInt(octet);
                if (number < 0 || number > 255 || octet.isEmpty()) return false;
            }
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean isIpv6Address(String value) {
        if (!value.contains(":")) return false;
        try {
            return java.net.InetAddress.getByName(value) instanceof java.net.Inet6Address;
        } catch (java.net.UnknownHostException exception) {
            return false;
        }
    }

    private static void validateDurations(Path file, String service, Map<?, ?> map, String parentPath, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && (!isScalar(value) || !isDuration(String.valueOf(value))))
                throw conversionFailure(file, service, parentPath + "." + key, value, "a supported duration");
        }
    }

    private static void validateBytes(Path file, String service, Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value != null && (!isScalar(value) || !isByteValue(String.valueOf(value))))
            throw conversionFailure(file, service, path, value, "a supported byte value");
    }

    private static void validateBoolean(Path file, String service, Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value != null && (!isScalar(value) || !(value instanceof Boolean
                || "true".equalsIgnoreCase(String.valueOf(value)) || "false".equalsIgnoreCase(String.valueOf(value)))))
            throw conversionFailure(file, service, path, value, "a boolean value");
    }

    private static void validateInteger(Path file, String service, Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value == null) return;
        try {
            if (!isScalar(value) || new BigInteger(String.valueOf(value)).compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                    || new BigInteger(String.valueOf(value)).compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0)
                throw conversionFailure(file, service, path, value, "an integer value");
        } catch (NumberFormatException exception) {
            throw conversionFailure(file, service, path, value, "an integer value", exception);
        }
    }

    private static void validateNumeric(Path file, String service, Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value == null) return;
        try {
            if (!isScalar(value) || !Double.isFinite(Double.parseDouble(String.valueOf(value))))
                throw conversionFailure(file, service, path, value, "a numeric value");
        } catch (NumberFormatException exception) {
            throw conversionFailure(file, service, path, value, "a numeric value", exception);
        }
    }

    private static void validateNestedBytes(Path file, String service, Map<?, ?> parent, String block, String key, String field, String path) {
        if (parent.get(block) instanceof Map<?, ?> nested && nested.get(key) instanceof Map<?, ?> values)
            validateBytes(file, service, values, field, path);
    }

    private static void validateNestedInteger(Path file, String service, Map<?, ?> parent, String block, String key, String path) {
        if (parent.get(block) instanceof Map<?, ?> nested) validateInteger(file, service, nested, key, path);
    }

    private static void validateNestedNumeric(Path file, String service, Map<?, ?> parent, String block, String key, String path) {
        if (parent.get(block) instanceof Map<?, ?> nested) validateNumeric(file, service, nested, key, path);
    }

    private static void validateNestedDurations(Path file, String service, Map<?, ?> parent, String block,
                                                String parentPath, String... keys) {
        if (parent.get(block) instanceof Map<?, ?> nested) validateDurations(file, service, nested, parentPath, keys);
    }

    private static boolean isScalar(Object value) {
        return !(value instanceof Map<?, ?> || value instanceof List<?>);
    }

    private static boolean isDuration(String value) {
        if ("0".equals(value)) return true;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)")
                .matcher(value);
        int offset = 0;
        BigDecimal totalNanos = BigDecimal.ZERO;
        while (matcher.find()) {
            if (matcher.start() != offset) return false;
            BigDecimal amount = new BigDecimal(matcher.group(1));
            BigDecimal multiplier = switch (matcher.group(2)) {
                case "ns" -> BigDecimal.ONE;
                case "us", "µs", "μs" -> BigDecimal.valueOf(1_000);
                case "ms" -> BigDecimal.valueOf(1_000_000);
                case "s" -> BigDecimal.valueOf(1_000_000_000L);
                case "m" -> BigDecimal.valueOf(60_000_000_000L);
                case "h" -> BigDecimal.valueOf(3_600_000_000_000L);
                default -> BigDecimal.ZERO;
            };
            totalNanos = totalNanos.add(amount.multiply(multiplier));
            if (totalNanos.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) return false;
            offset = matcher.end();
        }
        return offset == value.length() && offset > 0;
    }

    private static boolean isByteValue(String value) {
        try {
            if (value.matches("[+-]?\\d+")) {
                new BigInteger(value).longValueExact();
                return true;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)([kmg])b?", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(value);
            if (!matcher.matches()) return false;
            BigInteger multiplier = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "k" -> BigInteger.valueOf(1024);
                case "m" -> BigInteger.valueOf(1024L * 1024);
                case "g" -> BigInteger.valueOf(1024L * 1024 * 1024);
                default -> BigInteger.ZERO;
            };
            new BigInteger(matcher.group(1)).multiply(multiplier).longValueExact();
            return true;
        } catch (ArithmeticException | NumberFormatException exception) {
            return false;
        }
    }

    private static ComposeValidationException shapeFailure(Path file, String service, String path, String expected, Object found) {
        String actual = found instanceof Map<?, ?> ? "a YAML mapping" : found instanceof List<?> ? "a YAML list" : "scalar '" + found + "'";
        return new ComposeValidationException(file, path, service, "expected " + expected + ", but found " + actual + ".", null, null, null);
    }

    private static ComposeValidationException conversionFailure(Path file, String service, String path, Object value, String expected) {
        return conversionFailure(file, service, path, value, expected, new NumberFormatException("Value '" + value + "' is not " + expected + "."));
    }

    private static ComposeValidationException conversionFailure(Path file, String service, String path, Object value,
                                                                String expected, RuntimeException cause) {
        return new ComposeValidationException(file, path, service, "'" + value + "' is not " + expected + ".", null, null, cause);
    }

    private static Map<String, Path> buildProvenance(Object node, Path source) {
        Map<String, Path> result = new LinkedHashMap<>();
        addProvenance(node, "", source, result);
        return result;
    }

    private static void addProvenance(Object node, String path, Path source, Map<String, Path> result) {
        if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String child = appendPathSegment(path, String.valueOf(entry.getKey()));
                result.put(child, source);
                addProvenance(entry.getValue(), child, source, result);
            }
        } else if (node instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                String child = path + "[" + index + "]";
                result.put(child, source);
                addProvenance(list.get(index), child, source, result);
            }
        }
    }

    private static Map<String, Path> mergeProvenance(Map<String, Object> base, Map<String, Object> overlay,
                                                      Map<String, Object> merged, Map<String, Path> baseProvenance,
                                                      Map<String, Path> overlayProvenance) {
        Map<String, Path> result = new LinkedHashMap<>(baseProvenance);
        applyOverlayProvenance(base, overlay, merged, "", result, overlayProvenance);
        return result;
    }

    private static void applyOverlayProvenance(Map<String, Object> base, Map<String, Object> overlay,
                                               Map<String, Object> merged, String path,
                                               Map<String, Path> result, Map<String, Path> overlayProvenance) {
        for (var entry : overlay.entrySet()) {
            String key = entry.getKey();
            String child = appendPathSegment(path, key);
            Object before = base.get(key);
            Object after = entry.getValue();
            Object effective = merged.get(key);
            if (!base.containsKey(key)) {
                replaceProvenance(result, overlayProvenance, child, child);
            } else if (before instanceof Map<?, ?> baseMap && after instanceof Map<?, ?> overlayMap
                    && effective instanceof Map<?, ?> mergedMap) {
                applyOverlayProvenance(stringKeyMapUnchecked(baseMap), stringKeyMapUnchecked(overlayMap),
                        stringKeyMapUnchecked(mergedMap), child, result, overlayProvenance);
            } else if (before instanceof List<?> baseList && after instanceof List<?> overlayList
                    && effective instanceof List<?> mergedList) {
                if (REPLACE_FIELDS.contains(key)) {
                    replaceProvenance(result, overlayProvenance, child, child);
                    continue;
                }
                for (int index = 0; index < overlayList.size(); index++) {
                    int mergedIndex = mergedListIndex(key, baseList, overlayList, mergedList, index);
                    if (mergedIndex < 0) continue;
                    String sourcePath = child + "[" + index + "]";
                    String targetPath = child + "[" + mergedIndex + "]";
                    replaceProvenance(result, overlayProvenance, sourcePath, targetPath);
                }
            } else {
                replaceProvenance(result, overlayProvenance, child, child);
            }
        }
    }

    private static void replaceProvenance(Map<String, Path> target, Map<String, Path> source,
                                          String sourcePrefix, String targetPrefix) {
        target.keySet().removeIf(path -> isPathAtOrBelow(path, targetPrefix));
        for (var entry : source.entrySet()) {
            if (isPathAtOrBelow(entry.getKey(), sourcePrefix))
                target.put(targetPrefix + entry.getKey().substring(sourcePrefix.length()), entry.getValue());
        }
    }

    private static boolean isPathAtOrBelow(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + ".") || path.startsWith(prefix + "[");
    }

    private static int mergedListIndex(String field, List<?> base, List<?> overlay, List<?> merged, int overlayIndex) {
        Object overlayValue = overlay.get(overlayIndex);
        if (REPLACE_FIELDS.contains(field)) return overlayIndex;
        if (KEYED_LIST_FIELDS.contains(field) || TARGETED_LIST_FIELDS.contains(field) || "ports".equals(field)) {
            String key = listMergeKey(field, overlayValue);
            for (int index = 0; index < merged.size(); index++)
                if (key.equals(listMergeKey(field, merged.get(index)))) return index;
            return -1;
        }
        return base.size() + overlayIndex < merged.size() ? base.size() + overlayIndex : -1;
    }

    private static String listMergeKey(String field, Object value) {
        if (KEYED_LIST_FIELDS.contains(field)) return listEntryKey(value);
        if (TARGETED_LIST_FIELDS.contains(field)) return targetKey(value);
        if ("ports".equals(field)) return portKey(value);
        return String.valueOf(value);
    }

    private static String appendPathSegment(String path, String segment) {
        if (!segment.isEmpty() && segment.chars().allMatch(character -> Character.isLetterOrDigit(character)
                || character == '_' || character == '-')) return path.isEmpty() ? segment : path + "." + segment;
        String escaped = segment.replace("\\", "\\\\").replace("\"", "\\\"");
        return path + "[\"" + escaped + "\"]";
    }

    private static Path sourceFor(Path fallback, String propertyPath, String service, Map<String, Path> provenance) {
        if (propertyPath == null) return fallback;
        String candidate = propertyPath;
        if (service != null) {
            String displayPrefix = "services." + service;
            if (candidate.equals(displayPrefix) || candidate.startsWith(displayPrefix + ".")
                    || candidate.startsWith(displayPrefix + "[")) {
                String escapedPrefix = appendPathSegment("services", service);
                candidate = escapedPrefix + candidate.substring(displayPrefix.length());
            }
            candidate = candidate.replaceAll("\\.(\\d+)(?=\\.|$)", "[$1]");
        }
        while (!candidate.isEmpty()) {
            Path source = provenance.get(candidate);
            if (source != null) return source;
            int list = candidate.lastIndexOf('[');
            int member = candidate.lastIndexOf('.');
            candidate = list > member ? candidate.substring(0, list)
                    : member >= 0 ? candidate.substring(0, member) : "";
        }
        return fallback;
    }

    private static void validateServiceDependencies(Path file, String service, Object configured,
                                                    Map<?, ?> services) {
        List<String> references;
        if (configured instanceof Map<?, ?> map) {
            references = new ArrayList<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String dependency))
                    throw new ComposeValidationException(file, "services." + service + ".depends_on", service,
                            "dependency names must be strings.", null, null, null);
                if (!(entry.getValue() instanceof Map<?, ?> options))
                    throw new ComposeValidationException(file, "services." + service + ".depends_on." + dependency, service,
                            "dependency configuration must be a mapping.", null, null, null);
                Map<String, Object> configuration = new LinkedHashMap<>();
                for (var option : options.entrySet()) {
                    if (!(option.getKey() instanceof String key))
                        throw new ComposeValidationException(file, "services." + service + ".depends_on." + dependency, service,
                                "dependency option names must be strings.", null, null, null);
                    configuration.put(key, option.getValue());
                }
                for (String key : configuration.keySet()) {
                    if (!Set.of("condition", "restart", "required").contains(key))
                        throw new ComposeValidationException(file, "services." + service + ".depends_on." + dependency + "." + key, service,
                                "unsupported dependency option '" + key + "'.", null, null, null);
                }
                Object condition = configuration.get("condition");
                if (condition == null)
                    throw new ComposeValidationException(file, "services." + service + ".depends_on." + dependency, service,
                            "dependency configuration is missing property 'condition'.", null, null, null);
                if (!(condition instanceof String value) || !Set.of("service_started", "service_healthy",
                        "service_completed_successfully").contains(value))
                    throw new ComposeValidationException(file, "services." + service + ".depends_on." + dependency + ".condition", service,
                            "condition must be 'service_started', 'service_healthy', or 'service_completed_successfully'.", null, null, null);
                for (String booleanOption : List.of("restart", "required")) {
                    if (configuration.containsKey(booleanOption)
                            && !(configuration.get(booleanOption) instanceof Boolean))
                        throw new ComposeValidationException(file, "services." + service + ".depends_on." + dependency + "." + booleanOption, service,
                                booleanOption + " must be a boolean.", null, null, null);
                }
                references.add(dependency);
            }
        } else if (configured instanceof List<?> list) {
            references = new ArrayList<>();
            for (int index = 0; index < list.size(); index++) {
                if (!(list.get(index) instanceof String dependency))
                    throw new ComposeValidationException(file, "services." + service + ".depends_on." + index, service,
                            "dependency names must be strings.", null, null, null);
                references.add(dependency);
            }
        } else {
            throw new ComposeValidationException(file, "services." + service + ".depends_on", service,
                    "depends_on must be an array or mapping.", null, null, null);
        }
        for (String dependency : references) {
            if (!services.containsKey(dependency))
                throw new ComposeValidationException(file, "services." + service + ".depends_on." + dependency,
                        service, "service depends on undefined service '" + dependency + "'.", null, null, null);
        }
    }

    private static void validateServiceLinks(Path file, String service, Object configured, Map<?, ?> services) {
        String path = "services." + service + ".links";
        if (!(configured instanceof List<?> links))
            throw new ComposeValidationException(file, path, service, "links must be an array.", null, null, null);
        for (int index = 0; index < links.size(); index++) {
            if (!(links.get(index) instanceof String link))
                throw new ComposeValidationException(file, path + "." + index, service,
                        "link entries must be strings.", null, null, null);
            String[] parts = link.split(":", -1);
            if (parts.length > 2 || parts[0].isBlank() || parts.length == 2 && parts[1].isBlank())
                throw new ComposeValidationException(file, path + "." + index, service,
                        "link entries must be a service name or service:alias.", null, null, null);
            if (!services.containsKey(parts[0]))
                throw new ComposeValidationException(file, path + "." + index, service,
                        "link refers to undefined service '" + parts[0] + "'.", null, null, null);
        }
    }

    private static void validateServiceResources(Path file, String service, Object configured,
                                                 Object declared, String resourceType) {
        if (!(configured instanceof List<?> references)) return;
        Set<String> known = new java.util.LinkedHashSet<>(names(declared));
        for (Object reference : references) {
            String name = reference instanceof Map<?, ?> mapping
                    ? String.valueOf(mapping.get("source"))
                    : String.valueOf(reference);
            if (!known.contains(name))
                throw new ComposeValidationException(file, "services." + service + "." + resourceType + "." + name,
                        service, "service refers to undefined " + resourceType.substring(0, resourceType.length() - 1)
                        + " '" + name + "'.", null, null, null);
        }
    }

    private static List<?> serviceVolumeReferences(Object configured) {
        if (!(configured instanceof List<?> mounts)) return List.of();
        List<String> references = new ArrayList<>();
        for (Object mount : mounts) {
            if (mount instanceof Map<?, ?> mapping) {
                Object type = mapping.get("type");
                Object source = mapping.get("source");
                if (source instanceof String name && (type == null || "volume".equals(type)))
                    references.add(name);
            } else if (mount instanceof String value) {
                if (value.matches("[A-Za-z]:[\\\\/].*")) continue;
                int separator = value.indexOf(':');
                if (separator <= 0) continue;
                String source = value.substring(0, separator);
                if (source.startsWith("/") || source.startsWith("./") || source.startsWith("../")
                        || source.startsWith("~") || source.contains("${")) continue;
                references.add(source);
            }
        }
        return references;
    }

    private static void resolveEnvFiles(Path base, Map<String, Object> service, Path source, String name,
                                        Map<String, String> environmentOverrides) {
        Object envFile = service.get("env_file");
        if (envFile == null) return;
        List<?> files = envFile instanceof List<?> list ? list : List.of(envFile);
        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, String> process = environmentWithOverrides(environmentOverrides);
        try {
            Map<String, String> dotenv = parseEnvFile(base.resolve(".env"), process);
            for (Object item : files) {
                String path;
                boolean required = true;
                boolean rawFormat = false;
                if (item instanceof Map<?, ?> mapping) {
                    Object rawPath = mapping.get("path");
                    if (!(rawPath instanceof String value) || value.isBlank())
                        throw new IOException("env_file entries must define a non-empty path.");
                    path = value;
                    Object rawRequired = mapping.get("required");
                    if (Boolean.FALSE.equals(rawRequired) || "false".equalsIgnoreCase(String.valueOf(rawRequired)))
                        required = false;
                    Object format = mapping.get("format");
                    if (format != null) {
                        if (!(format instanceof String formatName) || !"raw".equals(formatName))
                            throw new IOException("Unsupported env_file format: " + format);
                        rawFormat = true;
                    }
                } else path = String.valueOf(item);
                Path envPath = base.resolve(path).normalize();
                if (!Files.isRegularFile(envPath)) {
                    if (!required) continue;
                    throw new IOException("env_file not found: " + envPath);
                }
                resolved.putAll(rawFormat ? parseEnvFile(envPath, true)
                        : parseServiceEnvFile(envPath, dotenv, process));
            }
        } catch (IOException | RuntimeException exception) {
            throw new ComposeValidationException(source, "services." + name + ".env_file", name, exception.getMessage(), null, null, exception);
        }
        Object existing = service.get("environment");
        if (existing instanceof Map<?, ?> map) map.forEach((k, v) -> {
            String key = String.valueOf(k);
            resolved.put(key, v == null ? process.get(key) : String.valueOf(v));
        });
        else if (existing instanceof List<?> list) for (Object pair : list) {
            String value = String.valueOf(pair); int split = value.indexOf('=');
            String key = split < 0 ? value : value.substring(0, split);
            resolved.put(key, split < 0 ? process.get(key) : value.substring(split + 1));
        }
        if (!resolved.isEmpty()) service.put("environment", Collections.unmodifiableMap(resolved));
    }

    private static Map<String, String> parseEnvFile(Path path, Map<String, String> process) throws IOException {
        return parseEnvFile(path, false, true, process);
    }

    private static Map<String, String> parseEnvFile(Path path, boolean rawFormat) throws IOException {
        return parseEnvFile(path, rawFormat, false, Map.of());
    }

    private static Map<String, String> parseEnvFile(Path path, boolean rawFormat, boolean bareKeysAreUnset,
                                                     Map<String, String> process) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) return result;
        List<String> lines = Files.readAllLines(path);
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            if (rawFormat) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int equals = line.indexOf('=');
                if (equals <= 0) continue;
                String key = line.substring(0, equals).trim();
                if (!key.isEmpty()) result.put(key, line.substring(equals + 1));
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("export ")) trimmed = trimmed.substring(7).trim();
            int equals = trimmed.indexOf('=');
            int colon = trimmed.indexOf(':');
            int separator = equals < 0 ? colon : colon < 0 ? equals : Math.min(equals, colon);
            if (separator == 0) continue;
            String key = (separator < 0 ? trimmed : trimmed.substring(0, separator)).trim();
            if (key.isEmpty()) continue;
            if (separator < 0) {
                result.put(key, bareKeysAreUnset ? null : "");
                continue;
            }
            String value = trimmed.substring(separator + 1).trim();
            if (!value.isEmpty() && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
                char quote = value.charAt(0);
                int closing = findClosingQuote(value, quote);
                while (closing < 0 && quote == '\'' && lineIndex + 1 < lines.size()) {
                    value += "\n" + lines.get(++lineIndex);
                    closing = findClosingQuote(value, quote);
                }
                if (closing < 0) throw new IOException("Unterminated quoted value for " + key + " in " + path);
                String trailing = value.substring(closing + 1).trim();
                if (!trailing.isEmpty() && !trailing.startsWith("#"))
                    throw new IOException("Unexpected characters after quoted value for " + key + " in " + path);
                value = unescapeEnvValue(value.substring(1, closing), quote);
                if (quote != '\'') value = interpolate(value, result, process);
            } else {
                int comment = value.indexOf(" #");
                if (comment >= 0) value = value.substring(0, comment).trim();
                value = interpolate(value, result, process);
            }
            result.put(key, value);
        }
        return result;
    }

    private static Map<String, String> parseServiceEnvFile(Path path, Map<String, String> dotenv,
                                                            Map<String, String> process) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path);
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String originalLine = lines.get(lineIndex);
            String line = originalLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).trim();

            int equals = line.indexOf('=');
            int colon = line.indexOf(':');
            int separator = equals < 0 ? colon : colon < 0 ? equals : Math.min(equals, colon);
            if (separator < 0) {
                if (!line.isEmpty() && process.containsKey(line)) result.put(line, process.get(line));
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (key.isEmpty()) continue;
            String value = line.substring(separator + 1).trim();
            if (value.isEmpty()) {
                result.put(key, "");
                continue;
            }

            char quote = value.charAt(0);
            if (quote == '\'' || quote == '"') {
                int closing = findClosingQuote(value, quote);
                while (closing < 0 && quote == '\'' && lineIndex + 1 < lines.size()) {
                    value += "\n" + lines.get(++lineIndex);
                    closing = findClosingQuote(value, quote);
                }
                if (closing < 0) throw new IOException("Unterminated quoted value for " + key + " in " + path);
                String parsed = unescapeEnvValue(value.substring(1, closing), quote);
                String trailing = value.substring(closing + 1).trim();
                if (!trailing.isEmpty() && !trailing.startsWith("#"))
                    throw new IOException("Unexpected characters after quoted value for " + key + " in " + path);
                result.put(key, quote == '\'' ? parsed : interpolate(parsed, dotenv, process));
                continue;
            }

            int comment = value.indexOf(" #");
            if (comment >= 0) value = value.substring(0, comment).trim();
            result.put(key, interpolate(value, dotenv, process));
        }
        return result;
    }

    private static int findClosingQuote(String value, char quote) {
        boolean escaped = false;
        for (int index = 1; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) escaped = false;
            else if (current == '\\') escaped = true;
            else if (current == quote) return index;
        }
        return -1;
    }

    private static String unescapeEnvValue(String value, char quote) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                result.append(current);
                continue;
            }
            char next = value.charAt(index + 1);
            if (quote == '\'' && next != '\'') {
                result.append(current);
                continue;
            }
            index++;
            if (quote == '"') {
                switch (next) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case '\\', '"' -> result.append(next);
                    case '$' -> result.append("$$");
                    default -> result.append('\\').append(next);
                }
            } else result.append(next);
        }
        return result.toString();
    }

    private static Map<String, String> environmentWithOverrides(Map<String, String> overrides) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        Map<String, String> environment = windows
                ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
                : new LinkedHashMap<>();
        environment.putAll(System.getenv());
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (key == null || key.isBlank()) throw new IllegalArgumentException("Environment override keys must not be blank.");
                if (value == null) throw new IllegalArgumentException("Environment override values must not be null: " + key);
                environment.put(key, value);
            });
        }
        return environment;
    }

    private static String interpolate(String text, Map<String, String> dotenv, Map<String, String> process) {
        return VariableInterpolator.expand(text, dotenv, process);
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay, String path) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (var entry : overlay.entrySet()) {
            String key = entry.getKey(); Object before = result.get(key), after = entry.getValue();
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (after instanceof MergeTagValue tagged) {
                result.put(key, tagged.tag().equals("!reset") ? resetValue(before, tagged.value()) : tagged.value());
                continue;
            }
            if (before instanceof Map<?, ?> a && after instanceof Map<?, ?> b && !REPLACE_FIELDS.contains(key)) {
                result.put(key, merge(stringKeyMapUnchecked(a), stringKeyMapUnchecked(b), childPath));
            } else if (before instanceof List<?> a && after instanceof List<?> b) {
                if (REPLACE_FIELDS.contains(key)) result.put(key, after);
                else if (KEYED_LIST_FIELDS.contains(key)) result.put(key, mergeKeyedList(a, b));
                else if (TARGETED_LIST_FIELDS.contains(key)) result.put(key, mergeTargetedList(a, b));
                else if ("ports".equals(key)) result.put(key, mergePorts(a, b));
                else if (DEDUPLICATED_LIST_FIELDS.contains(key)) result.put(key, mergeUniqueList(a, b));
                else result.put(key, appendAll(a, b));
            } else result.put(key, after);
        }
        return result;
    }

    private static Object resetValue(Object previous, Object supplied) {
        if (previous instanceof Map<?, ?>) return Map.of();
        if (previous instanceof List<?>) return List.of();
        if (supplied instanceof Map<?, ?>) return Map.of();
        if (supplied instanceof List<?>) return List.of();
        return null;
    }

    private static Map<String, Object> unwrapMergeTags(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(key, unwrapMergeTag(value)));
        return result;
    }

    private static Object unwrapMergeTag(Object value) {
        if (value instanceof MergeTagValue tagged) return unwrapMergeTag(tagged.value());
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> result.put(String.valueOf(key), unwrapMergeTag(nested)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(ComposeFileLoader::unwrapMergeTag).toList();
        return value;
    }

    private record MergeTagValue(String tag, Object value) { }

    private static final class ComposeSafeConstructor extends SafeConstructor {
        private ComposeSafeConstructor(LoaderOptions options) {
            super(options);
            yamlConstructors.put(new Tag("!reset"), new TaggedValueConstruct());
            yamlConstructors.put(new Tag("!override"), new TaggedValueConstruct());
        }

        private final class TaggedValueConstruct extends AbstractConstruct {
            @Override public Object construct(Node node) {
                Object value = switch (node.getNodeId()) {
                    case scalar -> constructTaggedScalar((ScalarNode) node);
                    case sequence -> constructSequence((SequenceNode) node);
                    case mapping -> constructMapping((MappingNode) node);
                    default -> throw new IllegalArgumentException("Unsupported YAML node for Compose merge tag: " + node.getNodeId());
                };
                return new MergeTagValue(node.getTag().getValue(), value);
            }

            private Object constructTaggedScalar(ScalarNode node) {
                String value = node.getValue();
                if (node.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN
                        && (value.equals("~") || value.equalsIgnoreCase("null"))) return null;
                return constructScalar(node);
            }
        }
    }

    private static List<Object> appendAll(List<?> base, List<?> overlay) {
        List<Object> merged = new ArrayList<>(base);
        merged.addAll(overlay);
        return List.copyOf(merged);
    }

    private static List<Object> mergeKeyedList(List<?> base, List<?> overlay) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Object value : base) entries.put(listEntryKey(value), value);
        for (Object value : overlay) entries.put(listEntryKey(value), value);
        return List.copyOf(entries.values());
    }

    private static List<Object> mergeTargetedList(List<?> base, List<?> overlay) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Object value : base) entries.put(targetKey(value), value);
        for (Object value : overlay) entries.put(targetKey(value), value);
        return List.copyOf(entries.values());
    }

    private static List<Object> mergeUniqueList(List<?> base, List<?> overlay) {
        java.util.LinkedHashSet<Object> entries = new java.util.LinkedHashSet<>(base);
        entries.addAll(overlay);
        return List.copyOf(entries);
    }

    private static List<Object> mergePorts(List<?> base, List<?> overlay) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Object value : base) entries.put(portKey(value), value);
        for (Object value : overlay) entries.put(portKey(value), value);
        return List.copyOf(entries.values());
    }

    private static String portKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object target = map.get("target");
            if (target != null) {
                Object ip = map.containsKey("ip") ? map.get("ip") : map.get("host_ip");
                Object published = map.get("published");
                Object protocol = map.get("protocol");
                String normalizedIp = ip == null ? "" : String.valueOf(ip);
                if (normalizedIp.startsWith("[") && normalizedIp.endsWith("]"))
                    normalizedIp = normalizedIp.substring(1, normalizedIp.length() - 1);
                return String.join("\u0000", "port", normalizedIp,
                        String.valueOf(target), published == null ? "" : String.valueOf(published),
                        protocol == null ? "tcp" : String.valueOf(protocol).toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (value instanceof String port) {
            String[] protocolSplit = port.split("/", 2);
            String protocol = (protocolSplit.length == 2 ? protocolSplit[1] : "tcp")
                    .toLowerCase(java.util.Locale.ROOT);
            List<String> segments = splitPortSegments(protocolSplit[0]);
            if (segments.size() >= 1 && segments.size() <= 3) {
                String ip = "", published = "", target;
                if (segments.size() == 1) target = segments.getFirst();
                else if (segments.size() == 2) { published = segments.get(0); target = segments.get(1); }
                else { ip = segments.get(0); published = segments.get(1); target = segments.get(2); }
                if (ip.startsWith("[") && ip.endsWith("]")) ip = ip.substring(1, ip.length() - 1);
                return String.join("\u0000", "port", ip, target, published, protocol);
            }
            if (segments.size() > 3) {
                String ip = String.join(":", segments.subList(0, segments.size() - 2));
                if (ip.contains(":")) {
                    String published = segments.get(segments.size() - 2);
                    String target = segments.getLast();
                    return String.join("\u0000", "port", ip, target, published, protocol);
                }
            }
        }
        return "value\u0000" + String.valueOf(value);
    }

    private static List<String> splitPortSegments(String value) {
        List<String> segments = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        int brackets = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '[') brackets++;
            else if (current == ']') brackets--;
            if (current == ':' && brackets == 0) {
                segments.add(segment.toString());
                segment.setLength(0);
            } else segment.append(current);
        }
        segments.add(segment.toString());
        return segments;
    }

    private static String listEntryKey(Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) return String.valueOf(map.keySet().iterator().next());
        String entry = String.valueOf(value);
        int separator = entry.indexOf('=');
        if (separator < 0) separator = entry.indexOf(':');
        return separator < 0 ? entry : entry.substring(0, separator);
    }

    private static String targetKey(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object target = map.get("target");
            if (target != null) return String.valueOf(target);
        }
        String entry = String.valueOf(value);
        int sourceTargetSeparator = entry.indexOf(':');
        if (sourceTargetSeparator < 0) return entry;
        if (sourceTargetSeparator == 1 && Character.isLetter(entry.charAt(0)) && entry.length() > 2
                && (entry.charAt(2) == '\\' || entry.charAt(2) == '/')) {
            int drivePathSeparator = entry.indexOf(':', sourceTargetSeparator + 1);
            if (drivePathSeparator >= 0) sourceTargetSeparator = drivePathSeparator;
            else if (entry.charAt(2) == '\\') return entry;
        }
        int targetStart = sourceTargetSeparator + 1;
        int modeSeparator = entry.indexOf(':', targetStart);
        if (modeSeparator == targetStart + 1 && Character.isLetter(entry.charAt(targetStart))
                && modeSeparator + 1 < entry.length()
                && (entry.charAt(modeSeparator + 1) == '\\' || entry.charAt(modeSeparator + 1) == '/'))
            modeSeparator = entry.indexOf(':', modeSeparator + 1);
        return modeSeparator < 0 ? entry.substring(targetStart) : entry.substring(targetStart, modeSeparator);
    }

    private static Map<String, Object> stringKeyMapUnchecked(Map<?, ?> input) {
        Map<String, Object> output = new LinkedHashMap<>(); input.forEach((k, v) -> output.put(String.valueOf(k), v)); return output;
    }
    private static Map<String, Object> stringKeyMap(Map<?, ?> input, Path file, String path) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new ComposeValidationException(file, path, "mapping keys must be strings.");
            output.put(key, entry.getValue());
        }
        return output;
    }
    private static List<String> names(Object value) {
        if (!(value instanceof Map<?, ?> map)) return List.of(); return map.keySet().stream().map(String::valueOf).toList();
    }
    private static Map<String, Object> extensions(Map<String, Object> root) {
        Map<String, Object> out = new LinkedHashMap<>(); root.forEach((k, v) -> { if (k.startsWith("x-")) out.put(k, v); }); return out;
    }
}
