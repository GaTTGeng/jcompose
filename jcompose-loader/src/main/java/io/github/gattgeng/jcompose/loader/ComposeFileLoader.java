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
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads Compose YAML, expands variables, resolves service env files, and merges overlays. */
public final class ComposeFileLoader {
    private static final List<String> DEFAULT_FILES = List.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml");
    private static final Set<String> REPLACE_FIELDS = Set.of("command", "entrypoint", "test");
    private static final Set<String> KEYED_LIST_FIELDS = Set.of("environment", "labels");
    private static final Set<String> TARGETED_LIST_FIELDS = Set.of("volumes", "secrets", "configs");

    /** Loads one Compose file. Conventional default names are discovered from the working directory upward. */
    public ComposeProject load(Path workingDirectory, String composeFileName) {
        Path file = resolve(workingDirectory, composeFileName);
        Map<String, Object> root = read(file);
        return toProject(file, root);
    }

    /** Returns whether the requested/default Compose file can be discovered from the working directory. */
    public boolean hasComposeFile(Path workingDirectory, String composeFileName) {
        return find(workingDirectory, composeFileName) != null;
    }

    /** Applies a documented incremental merge: mappings merge recursively and later scalars replace. */
    public ComposeProject loadMerged(Path workingDirectory, List<String> composeFiles) {
        if (composeFiles == null || composeFiles.isEmpty()) throw new IllegalArgumentException("At least one Compose file is required.");
        Map<String, Object> merged = null;
        Path base = null;
        for (String name : composeFiles) {
            Path file = resolve(workingDirectory, name);
            Map<String, Object> next = read(file);
            if (merged == null) { merged = unwrapMergeTags(next); base = file; }
            else merged = merge(merged, next, "");
        }
        return toProject(base, merged);
    }

    private static Path resolve(Path directory, String requested) {
        Path file = find(directory, requested);
        if (file != null) return file;
        Path root = directory.toAbsolutePath().normalize();
        throw new IllegalArgumentException("Compose file not found from " + root + ": " + requested);
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
    private static Map<String, Object> read(Path file) {
        try {
            String text = Files.readString(file);
            Map<String, String> envFile = parseEnvFile(file.getParent().resolve(".env"));
            String expanded = interpolate(text, envFile, System.getenv());
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object parsed = new Yaml(new ComposeSafeConstructor(options)).load(expanded);
            if (!(parsed instanceof Map<?, ?> raw)) throw new ComposeValidationException(file, "$", "the document must be a YAML mapping.");
            return stringKeyMap(raw, file, "$");
        } catch (ComposeValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ComposeValidationException(file, "$", null, "could not load the Compose document: " + exception.getMessage(), null, null, exception);
        }
    }

    private static ComposeProject toProject(Path file, Map<String, Object> root) {
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
            resolveEnvFiles(file.getParent(), service, file, name);
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

    private static void resolveEnvFiles(Path base, Map<String, Object> service, Path source, String name) {
        Object envFile = service.get("env_file");
        if (envFile == null) return;
        List<?> files = envFile instanceof List<?> list ? list : List.of(envFile);
        Map<String, String> resolved = new LinkedHashMap<>();
        try {
            for (Object item : files) {
                String path;
                boolean required = true;
                if (item instanceof Map<?, ?> mapping) {
                    Object rawPath = mapping.get("path");
                    if (!(rawPath instanceof String value) || value.isBlank())
                        throw new IOException("env_file entries must define a non-empty path.");
                    path = value;
                    Object rawRequired = mapping.get("required");
                    if (Boolean.FALSE.equals(rawRequired) || "false".equalsIgnoreCase(String.valueOf(rawRequired)))
                        required = false;
                } else path = String.valueOf(item);
                Path envPath = base.resolve(path).normalize();
                if (!Files.isRegularFile(envPath)) {
                    if (!required) continue;
                    throw new IOException("env_file not found: " + envPath);
                }
                resolved.putAll(parseEnvFile(envPath));
            }
        } catch (IOException exception) {
            throw new ComposeValidationException(source, "services." + name + ".env_file", name, exception.getMessage(), null, null, exception);
        }
        Object existing = service.get("environment");
        if (existing instanceof Map<?, ?> map) map.forEach((k, v) -> resolved.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        else if (existing instanceof List<?> list) for (Object pair : list) {
            String value = String.valueOf(pair); int split = value.indexOf('=');
            resolved.put(split < 0 ? value : value.substring(0, split), split < 0 ? "" : value.substring(split + 1));
        }
        if (!resolved.isEmpty()) service.put("environment", Collections.unmodifiableMap(resolved));
    }

    private static Map<String, String> parseEnvFile(Path path) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) return result;
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.startsWith("export ")) trimmed = trimmed.substring(7).trim();
            int equals = trimmed.indexOf('=');
            if (equals <= 0) continue;
            String key = trimmed.substring(0, equals).trim();
            String value = trimmed.substring(equals + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
                value = value.substring(1, value.length() - 1);
            else { int comment = value.indexOf(" #"); if (comment >= 0) value = value.substring(0, comment).trim(); }
            result.put(key, value);
        }
        return result;
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
        String[] segments = entry.split(":", -1);
        return segments.length >= 2 ? segments[1] : entry;
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
