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
    private static final Set<String> UNIQUE_LIST_FIELDS = Set.of("cap_add", "cap_drop", "constraints",
            "device_cgroup_rules", "expose", "external_links", "generic_resources", "preferences", "security_opt");

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
        Map<String, Object> root = read(file, environmentOverrides);
        return toProject(file, root, environmentOverrides);
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
        return toProject(file, read(file, environmentOverrides), environmentOverrides);
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
        Path base = null;
        for (String name : composeFiles) {
            Path file = resolve(workingDirectory, name);
            if (base == null) base = file;
            Map<String, Object> next = read(file, environmentOverrides, base.getParent().resolve(".env"));
            if (merged == null) merged = unwrapMergeTags(next);
            else merged = merge(merged, next, "");
        }
        return toProject(base, merged, environmentOverrides);
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
    private static Map<String, Object> read(Path file, Map<String, String> environmentOverrides) {
        return read(file, environmentOverrides, file.getParent().resolve(".env"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path file, Map<String, String> environmentOverrides, Path dotenvPath) {
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
            return stringKeyMap(raw, file, "$");
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
                                            Map<String, String> environmentOverrides) {
        root = unwrapMergeTags(root);
        Object rawServices = root.get("services");
        if (!(rawServices instanceof Map<?, ?> serviceMap) || serviceMap.isEmpty())
            throw new ComposeValidationException(file, "services", "the document must define at least one service.");
        Map<String, ServiceDefinition> services = new LinkedHashMap<>();
        for (var entry : serviceMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> config))
                throw new ComposeValidationException(file, "services." + name, name, "service configuration must be a mapping.", null, null, null);
            Map<String, Object> service = stringKeyMap(config, file, "services." + name);
            if (!service.containsKey("image") && !service.containsKey("build"))
                throw new ComposeValidationException(file, "services." + name, name, "service must define either image or build.", null, null, null);
            if (service.containsKey("network_mode") && service.containsKey("networks"))
                throw new ComposeValidationException(file, "services." + name + ".networks", name,
                        "service cannot combine network_mode with networks.", null, null, null);
            validateServiceDependencies(file, name, service.get("depends_on"), serviceMap);
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
                definitions(root.get("configs")), extensions(root));
    }

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

    private static void validateServiceDependencies(Path file, String service, Object configured,
                                                    Map<?, ?> services) {
        List<String> references;
        if (configured instanceof Map<?, ?> map) references = map.keySet().stream().map(String::valueOf).toList();
        else if (configured instanceof List<?> list) references = list.stream().map(String::valueOf).toList();
        else return;
        for (String dependency : references) {
            if (!services.containsKey(dependency))
                throw new ComposeValidationException(file, "services." + service + ".depends_on." + dependency,
                        service, "service depends on undefined service '" + dependency + "'.", null, null, null);
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
        try {
            Map<String, String> process = environmentWithOverrides(environmentOverrides);
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
        if (existing instanceof Map<?, ?> map) map.forEach((k, v) -> resolved.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        else if (existing instanceof List<?> list) for (Object pair : list) {
            String value = String.valueOf(pair); int split = value.indexOf('=');
            resolved.put(split < 0 ? value : value.substring(0, split), split < 0 ? null : value.substring(split + 1));
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
                if (!line.isEmpty()) result.put(line, "");
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
                else if (UNIQUE_LIST_FIELDS.contains(key)) result.put(key, mergeUniqueList(a, b));
                else if ("ports".equals(key)) result.put(key, mergePorts(a, b));
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
