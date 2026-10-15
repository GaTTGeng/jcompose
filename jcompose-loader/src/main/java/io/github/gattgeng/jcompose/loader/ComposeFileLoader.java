package io.github.gattgeng.jcompose.loader;

import io.github.gattgeng.jcompose.api.ComposeProject;
import io.github.gattgeng.jcompose.api.ComposeValidationException;
import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads Compose YAML, expands variables, resolves service env files, and merges overlays. */
public final class ComposeFileLoader {
    private static final List<String> DEFAULT_FILES = List.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml");
    private static final Set<String> REPLACE_FIELDS = Set.of("command", "entrypoint", "test");
    private static final Set<String> KEYED_LIST_FIELDS = Set.of("environment", "labels");
    private static final Set<String> TARGETED_LIST_FIELDS = Set.of("volumes", "secrets", "configs");

    /** Loads one Compose file. When the requested file is absent, conventional names are tried. */
    public ComposeProject load(Path workingDirectory, String composeFileName) {
        Path file = resolve(workingDirectory, composeFileName);
        Map<String, Object> root = read(file);
        return toProject(file, root);
    }

    /** Applies a documented incremental merge: mappings merge recursively and later scalars replace. */
    public ComposeProject loadMerged(Path workingDirectory, List<String> composeFiles) {
        if (composeFiles == null || composeFiles.isEmpty()) throw new IllegalArgumentException("At least one Compose file is required.");
        Map<String, Object> merged = null;
        Path base = null;
        for (String name : composeFiles) {
            Path file = resolve(workingDirectory, name);
            Map<String, Object> next = read(file);
            if (merged == null) { merged = next; base = file; }
            else merged = merge(merged, next, "");
        }
        return toProject(base, merged);
    }

    private static Path resolve(Path directory, String requested) {
        Path root = directory.toAbsolutePath().normalize();
        if (requested != null && !requested.isBlank()) {
            Path candidate = root.resolve(requested).normalize();
            if (Files.isRegularFile(candidate)) return candidate;
        }
        for (String name : DEFAULT_FILES) {
            Path candidate = root.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalArgumentException("Compose file not found under " + root + ": " + requested);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path file) {
        try {
            String text = Files.readString(file);
            Map<String, String> envFile = parseEnvFile(file.getParent().resolve(".env"));
            String expanded = interpolate(text, envFile, System.getenv());
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object parsed = new Yaml(new SafeConstructor(options)).load(expanded);
            if (!(parsed instanceof Map<?, ?> raw)) throw new ComposeValidationException(file, "$", "the document must be a YAML mapping.");
            return stringKeyMap(raw, file, "$ ".trim());
        } catch (ComposeValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ComposeValidationException(file, "$", null, "could not load the Compose document: " + exception.getMessage(), null, null, exception);
        }
    }

    private static ComposeProject toProject(Path file, Map<String, Object> root) {
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
                names(root.get("secrets")), names(root.get("configs")), extensions(root));
    }

    private static void resolveEnvFiles(Path base, Map<String, Object> service, Path source, String name) {
        Object envFile = service.get("env_file");
        if (envFile == null) return;
        List<?> files = envFile instanceof List<?> list ? list : List.of(envFile);
        Map<String, String> resolved = new LinkedHashMap<>();
        try {
            for (Object item : files) {
                String path = item instanceof Map<?, ?> m ? String.valueOf(m.get("path")) : String.valueOf(item);
                Path envPath = base.resolve(path).normalize();
                if (!Files.isRegularFile(envPath)) throw new IOException("env_file not found: " + envPath);
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
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            char ch = text.charAt(i);
            if (ch != '$') { out.append(ch); i++; continue; }
            if (i + 1 < text.length() && text.charAt(i + 1) == '$') { out.append('$'); i += 2; continue; }
            if (i + 1 >= text.length() || text.charAt(i + 1) != '{') { out.append('$'); i++; continue; }
            int end = text.indexOf('}', i + 2);
            if (end < 0) throw new IllegalArgumentException("unterminated variable expression");
            String expr = text.substring(i + 2, end);
            int op = expr.indexOf(":-"); boolean colon = op >= 0;
            if (op < 0) op = expr.indexOf('-');
            int required = expr.indexOf(":?"); boolean reqColon = required >= 0;
            if (required < 0) required = expr.indexOf('?');
            if (required >= 0 && (op < 0 || required < op)) op = required;
            String key = op < 0 ? expr : expr.substring(0, op);
            String operator = op < 0 ? "" : expr.substring(op, (expr.startsWith(":", op) ? op + 2 : op + 1));
            String fallback = op < 0 ? "" : expr.substring(op + operator.length());
            String value = process.containsKey(key) ? process.get(key) : dotenv.get(key);
            boolean unset = value == null;
            boolean blank = value == null || value.isEmpty();
            if (operator.equals("-") || operator.equals(":-")) out.append((operator.equals(":-") ? blank : unset) ? fallback : value);
            else if (operator.equals("?") || operator.equals(":?")) {
                if (operator.equals(":?") ? blank : unset) throw new IllegalArgumentException("required variable '" + key + "' is not set" + (fallback.isBlank() ? "" : ": " + fallback));
                out.append(value);
            } else out.append(value == null ? "" : value);
            i = end + 1;
        }
        return out.toString();
    }

    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay, String path) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (var entry : overlay.entrySet()) {
            String key = entry.getKey(); Object before = result.get(key), after = entry.getValue();
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (before instanceof Map<?, ?> a && after instanceof Map<?, ?> b && !REPLACE_FIELDS.contains(key)) {
                result.put(key, merge(stringKeyMapUnchecked(a), stringKeyMapUnchecked(b), childPath));
            } else if (before instanceof List<?> a && after instanceof List<?> b) {
                if (REPLACE_FIELDS.contains(key)) result.put(key, after);
                else if (KEYED_LIST_FIELDS.contains(key)) result.put(key, mergeKeyedList(a, b));
                else if (TARGETED_LIST_FIELDS.contains(key)) result.put(key, mergeTargetedList(a, b));
                else result.put(key, appendUnique(a, b));
            } else result.put(key, after);
        }
        return result;
    }

    private static List<Object> appendUnique(List<?> base, List<?> overlay) {
        List<Object> merged = new ArrayList<>(base);
        for (Object value : overlay) if (!merged.contains(value)) merged.add(value);
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
