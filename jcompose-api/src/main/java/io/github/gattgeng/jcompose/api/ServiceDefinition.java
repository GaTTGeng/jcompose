package io.github.gattgeng.jcompose.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One Compose service. The raw normalized mapping preserves fields the engine does not apply. */
public record ServiceDefinition(String name, Map<String, Object> configuration) {
    public ServiceDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        configuration = Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }
    /** Returns the configured service image reference. */
    public String image() { return string("image"); }
    /** Returns the configured container name, if set. */
    public String containerName() { return string("container_name"); }
    /** Returns the normalized build definition. */
    public Object build() { return configuration.get("build"); }
    /** Returns the service build definition as an immutable typed projection, or null when absent. */
    public BuildConfig buildConfig() {
        Object value = configuration.get("build");
        if (value instanceof String context)
            return new BuildConfig(context, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        if (!(value instanceof Map<?, ?> build)) return null;
        return new BuildConfig(text(build.get("context")), text(build.get("dockerfile")), buildArgs(build.get("args")),
                nullableStringList(build.get("cache_from")), nullableStringList(build.get("cache_to")),
                text(build.get("target")), nullableStringList(build.get("tags")), nullableStringMap(build.get("labels")),
                text(build.get("network")), nullableStringMap(keyValueMap(build.get("extra_hosts"))), booleanValue(build.get("privileged")),
                text(build.get("shm_size")), nullableStringList(build.get("platforms")), booleanValue(build.get("pull")),
                booleanValue(build.get("no_cache")), text(build.get("context_directory")));
    }
    /** Returns environment entries with null values preserved for host passthrough. */
    public Map<String, Object> environment() { return stringObjectMap(configuration.get("environment")); }
    /** Returns service labels as immutable string values. */
    public Map<String, String> labels() { return stringMap(configuration.get("labels")); }
    /** Returns the normalized healthcheck mapping, or an empty map when absent. */
    public Map<String, Object> healthcheck() { return objectMap(configuration.get("healthcheck")); }
    /** Returns the service healthcheck as a typed projection, or null when absent. */
    public ComposeHealthcheck healthcheckConfig() {
        Map<String, Object> healthcheck = objectMap(configuration.get("healthcheck"));
        if (healthcheck.isEmpty()) return null;
        Object configuredTest = healthcheck.get("test");
        List<String> test = configuredTest instanceof String command
                ? List.of("CMD-SHELL", command) : nullableStringList(configuredTest);
        return new ComposeHealthcheck(Boolean.TRUE.equals(booleanValue(healthcheck.get("disable"))), test,
                durationValue(healthcheck.get("interval")), durationValue(healthcheck.get("timeout")),
                integerValue(healthcheck.get("retries")), durationValue(healthcheck.get("start_period")),
                durationValue(healthcheck.get("start_interval")));
    }
    /** Returns the normalized deployment mapping, or an empty map when absent. */
    public Map<String, Object> deploy() { return objectMap(configuration.get("deploy")); }
    /** Returns the service deployment block as a typed projection, or null when absent. */
    public DeployConfig deployConfig() { return deployConfig(configuration.get("deploy")); }
    /** Returns the normalized logging mapping, or an empty map when absent. */
    public Map<String, Object> logging() { return objectMap(configuration.get("logging")); }
    /** Returns the service logging block as a typed projection, or null when absent. */
    public LoggingConfig loggingConfig() {
        if (!(configuration.get("logging") instanceof Map<?, ?> logging)) return null;
        return new LoggingConfig(text(logging.get("driver")), nullableStringMap(logging.get("options")));
    }
    /** Returns the normalized development mapping, or an empty map when absent. */
    public Map<String, Object> develop() { return objectMap(configuration.get("develop")); }
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
    /** Returns configured profile names. */
    public List<String> profiles() {
        return stringList(configuration.get("profiles"));
    }
    /** Returns extra host entries in Docker's {@code host:address} form. */
    public List<String> extraHosts() { return keyValueList(configuration.get("extra_hosts"), ":"); }
    /** Returns tmpfs declarations while preserving mapping entries. */
    public List<?> tmpfs() { return objectList(configuration.get("tmpfs")); }
    /** Returns env_file declarations while preserving long-form mapping entries. */
    public List<?> envFile() { return objectList(configuration.get("env_file")); }
    /** Returns secret grants while preserving long-form mapping entries. */
    public List<?> secrets() { return objectList(configuration.get("secrets")); }
    /** Returns config grants while preserving long-form mapping entries. */
    public List<?> configs() { return objectList(configuration.get("configs")); }
    /** Returns Linux capabilities to add. */
    public List<String> capAdd() { return stringList(configuration.get("cap_add")); }
    /** Returns Linux capabilities to drop. */
    public List<String> capDrop() { return stringList(configuration.get("cap_drop")); }
    /** Returns configured device mappings. */
    public List<String> devices() { return stringList(configuration.get("devices")); }
    /** Returns configured security options. */
    public List<String> securityOpt() { return stringList(configuration.get("security_opt")); }
    /** Returns configured DNS servers. */
    public List<String> dns() { return stringList(configuration.get("dns")); }
    /** Returns configured DNS resolver options. */
    public List<String> dnsOpt() { return stringList(configuration.get("dns_opt")); }
    /** Returns configured DNS search domains. */
    public List<String> dnsSearch() { return stringList(configuration.get("dns_search")); }
    /** Returns configured supplementary groups. */
    public List<String> groupAdd() { return stringList(configuration.get("group_add")); }
    /** Returns links to other Compose services. */
    public List<String> links() { return stringList(configuration.get("links")); }
    /** Returns kernel parameter values as strings. */
    public Map<String, String> sysctls() { return stringMap(configuration.get("sysctls")); }
    /** Returns service annotations as immutable string values. */
    public Map<String, String> annotations() { return stringMap(configuration.get("annotations")); }
    /** Returns the configured CPU shares value. */
    public String cpuShares() { return string("cpu_shares"); }
    /** Returns the configured CPU quota value. */
    public String cpuQuota() { return string("cpu_quota"); }
    /** Returns the configured CPU set value. */
    public String cpuset() { return string("cpuset"); }
    /** Returns configured shared-memory size in bytes. */
    public Long shmSize() { return byteSize("shm_size"); }
    /** Returns the configured memory limit in bytes. */
    public Long memory() { return byteSize("mem_limit"); }
    /** Returns the configured memory-plus-swap limit in bytes. */
    public Long memorySwap() { return byteSize("memswap_limit"); }
    /** Returns the configured memory reservation in bytes. */
    public Long memoryReservation() { return byteSize("mem_reservation"); }
    /** Returns the optional OOM-kill setting without collapsing explicit false to absence. */
    public Boolean oomKillDisable() {
        return configuration.get("oom_kill_disable") instanceof Boolean value ? value : null;
    }
    /** Returns the configured OOM score adjustment. */
    public String oomScoreAdj() { return string("oom_score_adj"); }
    /** Returns the original numeric suffix from an {@code on-failure:N} policy, or null otherwise. */
    public String restartMaxRetries() {
        String policy = restart();
        if (policy == null || !policy.startsWith("on-failure:")) return null;
        return policy.substring("on-failure:".length());
    }
    /** Returns the service restart policy. */
    public String restart() { return string("restart"); }
    /** Returns the configured network mode. */
    public String networkMode() { return string("network_mode"); }
    /** Returns the configured IPC namespace mode. */
    public String ipc() { return string("ipc"); }
    /** Returns the configured PID namespace mode. */
    public String pid() { return string("pid"); }
    /** Returns the configured hostname. */
    public String hostname() { return string("hostname"); }
    /** Returns the configured domain name. */
    public String domainname() { return string("domainname"); }
    /** Returns the configured container user. */
    public String user() { return string("user"); }
    /** Returns the configured working directory. */
    public String workingDir() { return string("working_dir"); }
    /** Returns whether the service requests a TTY. */
    public boolean tty() { return Boolean.TRUE.equals(configuration.get("tty")); }
    /** Returns whether the service keeps standard input open. */
    public boolean stdinOpen() { return Boolean.TRUE.equals(configuration.get("stdin_open")); }
    /** Returns whether the service runs in privileged mode. */
    public boolean privileged() { return Boolean.TRUE.equals(configuration.get("privileged")); }
    /** Returns whether the container root filesystem is read-only. */
    public boolean readOnly() { return Boolean.TRUE.equals(configuration.get("read_only")); }
    /** Returns the optional init-process setting. */
    public Boolean init() { return configuration.get("init") instanceof Boolean value ? value : null; }
    /** Returns the configured stop signal. */
    public String stopSignal() { return string("stop_signal"); }
    /** Returns the configured stop grace period as a duration, or null when absent. */
    public Duration stopGracePeriod() { return durationValue(configuration.get("stop_grace_period")); }
    /** Returns the configured platform selector. */
    public String platform() { return string("platform"); }
    /** Returns the configured image pull policy. */
    public String pullPolicy() { return string("pull_policy"); }
    /** Returns the configured MAC address. */
    public String macAddress() { return string("mac_address"); }
    /** Returns the configured cgroup parent. */
    public String cgroupParent() { return string("cgroup_parent"); }
    /** Returns the service name referenced by {@code extends}, if present. */
    public String extendsService() {
        return configuration.get("extends") instanceof Map<?, ?> value ? text(value.get("service")) : null;
    }
    /** Returns the file referenced by {@code extends}, if present. */
    public String extendsFile() {
        return configuration.get("extends") instanceof Map<?, ?> value ? text(value.get("file")) : null;
    }
    private static List<?> objectList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(ServiceDefinition::freeze).toList();
        return value == null ? List.of() : List.of(freeze(value));
    }
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> mapping)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        mapping.forEach((key, entry) -> result.put(String.valueOf(key), freeze(entry)));
        return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> mapping) {
            Map<String, Object> result = new LinkedHashMap<>();
            mapping.forEach((key, entry) -> result.put(String.valueOf(key), freeze(entry)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) return list.stream().map(ServiceDefinition::freeze).toList();
        return value;
    }
    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return value == null ? List.of() : List.of(String.valueOf(value));
    }
    private static List<String> nullableStringList(Object value) {
        return value == null ? null : stringList(value);
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
    private static Map<String, String> keyValueMap(Object value) {
        if (value instanceof Map<?, ?> mapping) return stringMap(mapping);
        if (value instanceof List<?> list) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Object item : list) {
                String entry = String.valueOf(item);
                int separator = entry.indexOf('=');
                if (separator < 0) separator = entry.indexOf(':');
                if (separator >= 0) result.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
            return Collections.unmodifiableMap(result);
        }
        return null;
    }
    private static Map<String, String> nullableStringMap(Object value) {
        return value == null ? null : stringMap(value);
    }
    private static Map<String, String> buildArgs(Object value) {
        if (value instanceof Map<?, ?> mapping) {
            Map<String, String> result = new LinkedHashMap<>();
            mapping.forEach((key, entry) -> result.put(String.valueOf(key), entry == null ? null : String.valueOf(entry)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Object item : list) {
                String entry = String.valueOf(item);
                int separator = entry.indexOf('=');
                result.put(separator < 0 ? entry : entry.substring(0, separator),
                        separator < 0 ? null : entry.substring(separator + 1));
            }
            return Collections.unmodifiableMap(result);
        }
        return null;
    }
    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)))
            return Boolean.valueOf(text);
        return null;
    }
    private static Integer integerValue(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value)).intValueExact(); }
        catch (ArithmeticException | NumberFormatException exception) { return null; }
    }
    private static Long longValue(Object value) {
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value)).longValueExact(); }
        catch (ArithmeticException | NumberFormatException exception) { return null; }
    }
    private static Double doubleValue(Object value) {
        if (value == null) return null;
        try { return Double.valueOf(String.valueOf(value)); }
        catch (NumberFormatException exception) { return null; }
    }
    private static Duration durationValue(Object value) {
        if (value == null) return null;
        String source = String.valueOf(value);
        if ("0".equals(source)) return Duration.ZERO;
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)")
                .matcher(source);
        int offset = 0;
        BigDecimal nanos = BigDecimal.ZERO;
        while (matcher.find()) {
            if (matcher.start() != offset) return null;
            BigDecimal multiplier = switch (matcher.group(2)) {
                case "ns" -> BigDecimal.ONE;
                case "us", "µs", "μs" -> BigDecimal.valueOf(1_000);
                case "ms" -> BigDecimal.valueOf(1_000_000);
                case "s" -> BigDecimal.valueOf(1_000_000_000L);
                case "m" -> BigDecimal.valueOf(60_000_000_000L);
                case "h" -> BigDecimal.valueOf(3_600_000_000_000L);
                default -> BigDecimal.ZERO;
            };
            nanos = nanos.add(new BigDecimal(matcher.group(1)).multiply(multiplier));
            offset = matcher.end();
        }
        if (offset == 0 || offset != source.length()) return null;
        try { return Duration.ofNanos(nanos.toBigIntegerExact().longValueExact()); }
        catch (ArithmeticException exception) { return null; }
    }
    private static DeployConfig deployConfig(Object value) {
        if (!(value instanceof Map<?, ?> deploy)) return null;
        return new DeployConfig(integerValue(deploy.get("replicas")), deployResources(deploy.get("resources")),
                deployRestartPolicy(deploy.get("restart_policy")), deployPlacement(deploy.get("placement")),
                deployUpdate(deploy.get("update_config")), deployRollback(deploy.get("rollback_config")),
                nullableStringMap(deploy.get("labels")), text(deploy.get("mode")),
                deployEndpointMode(deploy.get("endpoint_mode")));
    }
    private static DeployConfig.Resources deployResources(Object value) {
        if (!(value instanceof Map<?, ?> resources)) return null;
        DeployConfig.Limits limits = null;
        if (resources.get("limits") instanceof Map<?, ?> mapping)
            limits = new DeployConfig.Limits(byteSizeValue(mapping.get("memory"), "deploy.resources.limits.memory"),
                    text(mapping.get("nano_cpus")), longValue(mapping.get("cpus")));
        DeployConfig.Reservations reservations = null;
        if (resources.get("reservations") instanceof Map<?, ?> mapping)
            reservations = new DeployConfig.Reservations(byteSizeValue(mapping.get("memory"), "deploy.resources.reservations.memory"),
                    text(mapping.get("nano_cpus")));
        return new DeployConfig.Resources(limits, reservations);
    }
    private static DeployConfig.RestartPolicy deployRestartPolicy(Object value) {
        if (!(value instanceof Map<?, ?> policy)) return null;
        return new DeployConfig.RestartPolicy(text(policy.get("condition")), integerValue(policy.get("max_retries")),
                durationValue(policy.get("delay")), durationValue(policy.get("window")), durationValue(policy.get("period")));
    }
    private static DeployConfig.Placement deployPlacement(Object value) {
        if (!(value instanceof Map<?, ?> placement)) return null;
        return new DeployConfig.Placement(nullableStringList(placement.get("constraints")));
    }
    private static DeployConfig.UpdateConfig deployUpdate(Object value) {
        if (!(value instanceof Map<?, ?> config)) return null;
        return new DeployConfig.UpdateConfig(text(config.get("parallelism")), durationValue(config.get("delay")),
                text(config.get("order")), durationValue(config.get("monitor")), text(config.get("failure_action")),
                doubleValue(config.get("max_failure_ratio")));
    }
    private static DeployConfig.RollbackConfig deployRollback(Object value) {
        if (!(value instanceof Map<?, ?> config)) return null;
        return new DeployConfig.RollbackConfig(text(config.get("parallelism")), durationValue(config.get("delay")),
                text(config.get("order")), durationValue(config.get("monitor")), text(config.get("failure_action")),
                doubleValue(config.get("max_failure_ratio")));
    }
    private static DeployConfig.EndpointMode deployEndpointMode(Object value) {
        if (!(value instanceof Map<?, ?> endpointMode)) return null;
        return new DeployConfig.EndpointMode(text(endpointMode.get("mode")));
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

    private Long byteSize(String key) {
        return byteSizeValue(configuration.get(key), key);
    }

    private static Long byteSizeValue(Object value, String key) {
        if (value == null) return null;
        String source = String.valueOf(value).trim();
        Matcher matcher = Pattern.compile("(?i)^([+-]?\\d+(?:\\.\\d+)?)\\s*(b|k|kb|kib|m|mb|mib|g|gb|gib|t|tb|tib|p|pb|pib)?$")
                .matcher(source);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid byte size for " + key + ": " + source);
        long multiplier = switch (matcher.group(2) == null ? "b" : matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "b" -> 1L;
            case "k", "kb", "kib" -> 1024L;
            case "m", "mb", "mib" -> 1024L * 1024;
            case "g", "gb", "gib" -> 1024L * 1024 * 1024;
            case "t", "tb", "tib" -> 1024L * 1024 * 1024 * 1024;
            case "p", "pb", "pib" -> 1024L * 1024 * 1024 * 1024 * 1024;
            default -> throw new IllegalArgumentException("Invalid byte size for " + key + ": " + source);
        };
        try {
            return new BigDecimal(matcher.group(1)).multiply(BigDecimal.valueOf(multiplier)).toBigInteger().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Byte size is outside the supported range for " + key + ": " + source, exception);
        }
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
