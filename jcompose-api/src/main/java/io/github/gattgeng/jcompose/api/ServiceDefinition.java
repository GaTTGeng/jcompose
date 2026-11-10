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
    public String containerName() { return string("container_name"); }
    public Object build() { return configuration.get("build"); }
    /** Returns environment entries with null values preserved for host passthrough. */
    public Map<String, Object> environment() { return stringObjectMap(configuration.get("environment")); }
    /** Returns service labels as immutable string values. */
    public Map<String, String> labels() { return stringMap(configuration.get("labels")); }
    /** Returns a scalar Compose command as one item, without shell-splitting it. */
    public List<String> command() { return stringList(configuration.get("command")); }
    /** Returns a scalar Compose entrypoint as one item, without shell-splitting it. */
    public List<String> entrypoint() { return stringList(configuration.get("entrypoint")); }
    /** Returns dependency service names in declaration order, for either Compose syntax. */
    public List<String> dependsOn() {
        Object value = configuration.get("depends_on");
        if (value instanceof Map<?, ?> dependencies) return dependencies.keySet().stream().map(String::valueOf).toList();
        return stringList(value);
    }
    /** Returns attached network names in declaration order, for either Compose syntax. */
    public List<String> networks() {
        Object value = configuration.get("networks");
        if (value instanceof Map<?, ?> networks) return networks.keySet().stream().map(String::valueOf).toList();
        return stringList(value);
    }
    /** Returns the service's port mappings in Docker Compose short or long syntax. */
    public List<ComposeServicePort> ports() {
        Object value = configuration.get("ports");
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(ServiceDefinition::port).toList();
    }
    /** Returns volume declarations while preserving long-form mapping entries. */
    public List<?> volumes() { return objectList(configuration.get("volumes")); }
    public List<String> profiles() {
        return stringList(configuration.get("profiles"));
    }
    public List<String> extraHosts() { return keyValueList(configuration.get("extra_hosts"), ":"); }
    public List<String> capAdd() { return stringList(configuration.get("cap_add")); }
    public List<String> capDrop() { return stringList(configuration.get("cap_drop")); }
    public List<String> devices() { return stringList(configuration.get("devices")); }
    public List<String> securityOpt() { return stringList(configuration.get("security_opt")); }
    public List<String> dns() { return stringList(configuration.get("dns")); }
    public List<String> dnsOpt() { return stringList(configuration.get("dns_opt")); }
    public List<String> dnsSearch() { return stringList(configuration.get("dns_search")); }
    public List<String> groupAdd() { return stringList(configuration.get("group_add")); }
    public List<String> links() { return stringList(configuration.get("links")); }
    public String restart() { return string("restart"); }
    public String networkMode() { return string("network_mode"); }
    public String ipc() { return string("ipc"); }
    public String pid() { return string("pid"); }
    public String hostname() { return string("hostname"); }
    public String domainname() { return string("domainname"); }
    public String user() { return string("user"); }
    public String workingDir() { return string("working_dir"); }
    public boolean tty() { return Boolean.TRUE.equals(configuration.get("tty")); }
    public boolean stdinOpen() { return Boolean.TRUE.equals(configuration.get("stdin_open")); }
    public boolean privileged() { return Boolean.TRUE.equals(configuration.get("privileged")); }
    public boolean readOnly() { return Boolean.TRUE.equals(configuration.get("read_only")); }
    public Boolean init() { return configuration.get("init") instanceof Boolean value ? value : null; }
    public String stopSignal() { return string("stop_signal"); }
    public String stopGracePeriod() { return string("stop_grace_period"); }
    public String platform() { return string("platform"); }
    public String pullPolicy() { return string("pull_policy"); }
    public String macAddress() { return string("mac_address"); }
    public String cgroupParent() { return string("cgroup_parent"); }
    public String extendsService() {
        return configuration.get("extends") instanceof Map<?, ?> value ? text(value.get("service")) : null;
    }
    public String extendsFile() {
        return configuration.get("extends") instanceof Map<?, ?> value ? text(value.get("file")) : null;
    }
    private static List<?> objectList(Object value) {
        if (value instanceof List<?> list) return List.copyOf(list);
        return value == null ? List.of() : List.of(value);
    }
    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return value == null ? List.of() : List.of(String.valueOf(value));
    }
    private static Map<String, Object> stringObjectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> mapping) {
            mapping.forEach((key, entry) -> result.put(String.valueOf(key), entry));
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                String entry = String.valueOf(item);
                int separator = entry.indexOf('=');
                result.put(separator < 0 ? entry : entry.substring(0, separator),
                        separator < 0 ? null : entry.substring(separator + 1));
            }
        }
        return Collections.unmodifiableMap(result);
    }
    private static Map<String, String> stringMap(Object value) {
        if (value instanceof Map<?, ?> mapping) {
            Map<String, String> result = new LinkedHashMap<>();
            mapping.forEach((key, entry) -> result.put(String.valueOf(key), entry == null ? "" : String.valueOf(entry)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Object item : list) {
                String label = String.valueOf(item);
                int separator = label.indexOf('=');
                result.put(separator < 0 ? label : label.substring(0, separator),
                        separator < 0 ? "" : label.substring(separator + 1));
            }
            return Collections.unmodifiableMap(result);
        }
        return Map.of();
    }
    private static List<String> keyValueList(Object value, String separator) {
        if (value instanceof Map<?, ?> mapping) {
            return mapping.entrySet().stream()
                    .map(entry -> String.valueOf(entry.getKey()) + separator + String.valueOf(entry.getValue()))
                    .toList();
        }
        return stringList(value);
    }
    private String string(String key) {
        Object value = configuration.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

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

    private static String defaultText(Object value, String fallback) {
        String text = text(value);
        return text == null ? fallback : text;
    }
}
