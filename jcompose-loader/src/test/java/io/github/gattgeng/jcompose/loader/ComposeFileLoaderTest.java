package io.github.gattgeng.jcompose.loader;

import io.github.gattgeng.jcompose.api.ComposeValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeFileLoaderTest {
    @TempDir
    Path directory;

    @Test
    void retainsEffectiveInterpolationEnvironmentWithProcessValuesTakingPrecedence() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: "${JC_TEST_PROCESS}:${JC_TEST_DOTENV}"
                """);
        Files.writeString(directory.resolve(".env"), """
                JC_TEST_PROCESS=dotenv-process
                JC_TEST_DOTENV=from-dotenv
                JC_TEST_UNUSED=unused
                """);

        var project = new ComposeFileLoader().load(directory, null, Map.of("JC_TEST_PROCESS", "from-process"));

        assertEquals("from-process:from-dotenv", project.services().get("app").configuration().get("image"));
        assertEquals(Map.of("JC_TEST_PROCESS", "from-process", "JC_TEST_DOTENV", "from-dotenv",
                "JC_TEST_UNUSED", "unused"), project.environment().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("JC_TEST_"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    @Test
    void loadsProfileServiceWithoutImageOrBuildUntilRuntimeSelection() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  helper:
                    profiles: [tools]
                    command: ["true"]
                  app:
                    image: alpine
                """);

        var project = new ComposeFileLoader().load(directory, null);

        assertEquals(List.of("tools"), project.services().get("helper").profiles());
        assertEquals("true", ((List<?>) project.services().get("helper").configuration().get("command")).getFirst());
        assertNull(project.services().get("helper").image());
        assertNull(project.services().get("helper").build());
    }

    @Test
    void includesLocalComposeResourcesAndRebasesTheirRelativePaths() throws Exception {
        Path includedDirectory = Files.createDirectories(directory.resolve("backend"));
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  frontend:
                    image: nginx
                include:
                  - path: ./backend/compose.yaml
                """);
        Files.writeString(includedDirectory.resolve("compose.yaml"), """
                services:
                  api:
                    build: .
                    env_file: api.env
                    volumes:
                      - ./data:/var/lib/app
                    networks: [internal]
                networks:
                  internal: {}
                configs:
                  app-config:
                    file: ./app.conf
                """);
        Files.writeString(includedDirectory.resolve("api.env"), "MODE=test\n");

        var project = new ComposeFileLoader().loadProject(directory, "compose.yaml");

        var api = project.services().get("api").configuration();
        assertEquals("backend", api.get("build"));
        assertEquals("backend/api.env", api.get("env_file"));
        assertEquals("./backend/data:/var/lib/app", ((List<?>) api.get("volumes")).getFirst());
        assertEquals("backend/app.conf", ((Map<?, ?>) project.configDefinitions().get("app-config")).get("file"));
        assertEquals(List.of("api", "frontend"), project.services().keySet().stream().sorted().toList());
    }

    @Test
    void mergesIncludedComposeFileListBeforeImportingResources() throws Exception {
        Path includedDirectory = Files.createDirectories(directory.resolve("backend"));
        Files.writeString(directory.resolve("compose.yaml"), """
                include:
                  - path:
                      - ./backend/compose.yaml
                      - ./backend/compose.override.yaml
                """);
        Files.writeString(includedDirectory.resolve("compose.yaml"), """
                services:
                  api:
                    build: .
                    environment:
                      BASE: kept
                    volumes:
                      - ./data:/data
                networks:
                  internal:
                    labels:
                      owner: backend
                """);
        Files.writeString(includedDirectory.resolve("compose.override.yaml"), """
                services:
                  api:
                    environment:
                      OVERLAY: merged
                    volumes:
                      - ./cache:/cache
                networks:
                  internal:
                    labels:
                      tier: private
                """);

        var project = new ComposeFileLoader().load(directory, "compose.yaml");

        var api = project.services().get("api").configuration();
        assertEquals("backend", api.get("build"));
        assertEquals(Map.of("BASE", "kept", "OVERLAY", "merged"), api.get("environment"));
        assertEquals(List.of("./backend/data:/data", "./backend/cache:/cache"), api.get("volumes"));
        assertEquals(Map.of("owner", "backend", "tier", "private"),
                ((Map<?, ?>) project.networkDefinitions().get("internal")).get("labels"));
    }

    @Test
    void shortIncludeUsesIncludedFileDirectoryForRelativePaths() throws Exception {
        Path includedDirectory = Files.createDirectories(directory.resolve("backend"));
        Files.writeString(directory.resolve("compose.yaml"), """
                include: ./backend/compose.yaml
                """);
        Files.writeString(includedDirectory.resolve("compose.yaml"), """
                services:
                  api:
                    build: .
                    volumes:
                      - ./data:/data
                """);

        var project = new ComposeFileLoader().load(directory, "compose.yaml");

        var api = project.services().get("api").configuration();
        assertEquals("backend", api.get("build"));
        assertEquals(List.of("./backend/data:/data"), api.get("volumes"));
    }

    @Test
    void includeProjectDirectoryOverridesIncludedFileDirectory() throws Exception {
        Path includedDirectory = Files.createDirectories(directory.resolve("backend"));
        Files.createDirectories(directory.resolve("assets"));
        Files.writeString(directory.resolve("compose.yaml"), """
                include:
                  - path: ./backend/compose.yaml
                    project_directory: ./assets
                """);
        Files.writeString(includedDirectory.resolve("compose.yaml"), """
                services:
                  api:
                    build: .
                    volumes:
                      - ./data:/data
                """);

        var project = new ComposeFileLoader().load(directory, "compose.yaml");

        var api = project.services().get("api").configuration();
        assertEquals("assets", api.get("build"));
        assertEquals(List.of("./assets/data:/data"), api.get("volumes"));
    }

    @Test
    void includeEnvFilesMergeInOrderAndParentEnvironmentWins() throws Exception {
        Path includedDirectory = Files.createDirectories(directory.resolve("backend"));
        Files.writeString(directory.resolve("compose.yaml"), """
                include:
                  - path: ./backend/compose.yaml
                    env_file:
                      - ./backend/first.env
                      - ./backend/second.env
                """);
        Files.writeString(directory.resolve(".env"), "JC_TEST_INCLUDE_PARENT_WINS=parent\n");
        Files.writeString(includedDirectory.resolve("first.env"), """
                JC_TEST_INCLUDE_BASE=first
                JC_TEST_INCLUDE_OVERRIDE=first
                JC_TEST_INCLUDE_PARENT_WINS=included
                """);
        Files.writeString(includedDirectory.resolve("second.env"), "JC_TEST_INCLUDE_OVERRIDE=second\n");
        Files.writeString(includedDirectory.resolve("compose.yaml"), """
                services:
                  api:
                    image: "${JC_TEST_INCLUDE_BASE}:${JC_TEST_INCLUDE_OVERRIDE}:${JC_TEST_INCLUDE_PARENT_WINS}"
                """);

        var project = new ComposeFileLoader().load(directory, "compose.yaml", Map.of());

        assertEquals("first:second:parent", project.services().get("api").image());
    }

    @Test
    void rejectsConflictingAndRemoteIncludesWithSourceContext() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                include: ./included.yaml
                """);
        Files.writeString(directory.resolve("included.yaml"), """
                services:
                  app:
                    image: nginx
                """);

        ComposeValidationException conflict = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, "compose.yaml"));
        assertEquals(composeFile, conflict.sourceFile());
        assertEquals("include.0.path", conflict.propertyPath());

        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                include: https://example.invalid/compose.yaml
                """);
        ComposeValidationException remote = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, "compose.yaml"));
        assertEquals("include.0.path", remote.propertyPath());
    }

    @Test
    void rejectsProfilesThatAreNotStringArrays() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                    profiles: debug
                """);
        ComposeValidationException scalar = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, null));
        assertEquals("services.app.profiles", scalar.propertyPath());

        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                    profiles: [1]
                """);
        ComposeValidationException nonString = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, null));
        assertEquals("services.app.profiles.0", nonString.propertyPath());
    }

    @Test
    void rejectsNonBooleanAttachValue() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                    attach: falsey
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, null));

        assertEquals("services.app.attach", exception.propertyPath());
        assertEquals("app", exception.serviceName());
    }

    @Test
    void retainsValidDevelopWatchRules() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                    develop:
                      watch:
                        - action: sync
                          path: ./src
                          target: /app/src
                          initial_sync: true
                          ignore: [generated, '*.tmp']
                          include: ['*.java']
                        - action: sync+exec
                          path: ./config
                          target: /etc/app
                          exec:
                            command: [app, reload]
                            user: app
                            privileged: false
                            working_dir: /app
                            environment:
                              MODE: development
                        - action: restart
                          path: ./app.properties
                        - action: rebuild
                          path: ./pom.xml
                """);

        var project = new ComposeFileLoader().load(directory, null);

        Map<?, ?> develop = (Map<?, ?>) project.services().get("app").configuration().get("develop");
        List<?> rules = (List<?>) develop.get("watch");
        assertEquals(4, rules.size());
        assertEquals("sync+exec", ((Map<?, ?>) rules.get(1)).get("action"));
    }

    @Test
    void rejectsInvalidDevelopWatchRulesWithComposePaths() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        ComposeFileLoader loader = new ComposeFileLoader();
        for (var invalid : List.of(
                Map.entry("develop: invalid", "services.app.develop"),
                Map.entry("develop:\n      watch: {}", "services.app.develop.watch"),
                Map.entry("develop:\n      watch:\n        - action: rebuild", "services.app.develop.watch[0].path"),
                Map.entry("develop:\n      watch:\n        - path: ./src\n          action: delete", "services.app.develop.watch[0].action"),
                Map.entry("develop:\n      watch:\n        - path: ./src\n          action: sync", "services.app.develop.watch[0].target"),
                Map.entry("develop:\n      watch:\n        - path: ./src\n          action: rebuild\n          target: /app", "services.app.develop.watch[0].target"),
                Map.entry("develop:\n      watch:\n        - path: ./src\n          action: rebuild\n          initial_sync: true", "services.app.develop.watch[0].initial_sync"),
                Map.entry("develop:\n      watch:\n        - path: C:/src\n          action: rebuild", "services.app.develop.watch[0].path"),
                Map.entry("develop:\n      watch:\n        - path: ./src\n          action: rebuild\n          ignore: generated", "services.app.develop.watch[0].ignore"),
                Map.entry("develop:\n      watch:\n        - path: ./src\n          action: sync+exec\n          target: /app\n          exec: {}", "services.app.develop.watch[0].exec.command"),
                Map.entry("develop:\n      watch:\n        - path: ./src\n          action: sync\n          target: /app\n          unexpected: true", "services.app.develop.watch[0].unexpected"))) {
            Files.writeString(composeFile, "services:\n  app:\n    image: alpine\n    " + invalid.getKey() + "\n");

            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> loader.load(directory, null));

            assertEquals(invalid.getValue(), exception.propertyPath());
            assertEquals("app", exception.serviceName());
        }
    }

    @Test
    void validatesPostStartAndPreStopHookDefinitions() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                    post_start:
                      - command: [sh, -c, 'echo started']
                        user: root
                        privileged: true
                        working_dir: /app
                        environment: [HOOK=post]
                    pre_stop:
                      - command: ./shutdown.sh
                        environment:
                          HOOK: pre
                    pre_start:
                      - image: busybox:1.36
                        command: [sh, -c, 'echo initialized']
                        per_replica: false
                      - command: ./prepare.sh
                """);

        var project = new ComposeFileLoader().load(directory, null);

        assertEquals(1, ((List<?>) project.services().get("app").configuration().get("post_start")).size());
        assertEquals(1, ((List<?>) project.services().get("app").configuration().get("pre_stop")).size());
        assertEquals(2, ((List<?>) project.services().get("app").configuration().get("pre_start")).size());
    }

    @Test
    void rejectsInvalidLifecycleHooksWithComposePaths() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        ComposeFileLoader loader = new ComposeFileLoader();
        for (var invalid : List.of(
                Map.entry("post_start: {}", "services.app.post_start"),
                Map.entry("post_start:\n      - user: app", "services.app.post_start[0].command"),
                Map.entry("pre_stop:\n      - command: [sh, 4]", "services.app.pre_stop[0].command"),
                Map.entry("pre_stop:\n      - command: true", "services.app.pre_stop[0].command"),
                Map.entry("post_start:\n      - command: echo ok\n        unexpected: true", "services.app.post_start[0].unexpected"),
                Map.entry("pre_stop:\n      - command: echo ok\n        privileged: \"yes\"", "services.app.pre_stop[0].privileged"))) {
            Files.writeString(composeFile, "services:\n  app:\n    image: alpine\n    " + invalid.getKey() + "\n");

            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> loader.load(directory, null));

            assertEquals(invalid.getValue(), exception.propertyPath());
            assertEquals("app", exception.serviceName());
            assertEquals(composeFile, exception.sourceFile());
        }
    }

    @Test
    void rejectsInvalidPreStartHookDefinitionsWithComposePaths() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        ComposeFileLoader loader = new ComposeFileLoader();
        for (var invalid : List.of(
                Map.entry("pre_start:\n      - image: \"\"", "services.app.pre_start[0].image"),
                Map.entry("pre_start:\n      - command: echo ok\n        per_replica: \"yes\"", "services.app.pre_start[0].per_replica"),
                Map.entry("pre_start:\n      - command: echo ok\n        image: 4", "services.app.pre_start[0].image"))) {
            Files.writeString(composeFile, "services:\n  app:\n    image: alpine\n    " + invalid.getKey() + "\n");
            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> loader.load(directory, null));
            assertEquals(invalid.getValue(), exception.propertyPath());
            assertEquals("app", exception.serviceName());
        }
    }

    @Test
    void validatesComposeScalarFieldsWithPropertyContext() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        ComposeFileLoader loader = new ComposeFileLoader();
        for (var invalid : List.of(
                Map.entry("healthcheck:\n      interval: eventually", "services.app.healthcheck.interval"),
                Map.entry("shm_size: huge", "services.app.shm_size"),
                Map.entry("deploy:\n      replicas: many", "services.app.deploy.replicas"),
                Map.entry("privileged: sometimes", "services.app.privileged"),
                Map.entry("environment: invalid", "services.app.environment"),
                Map.entry("build: []", "services.app.build"))) {
            Files.writeString(composeFile, "services:\n  app:\n    image: alpine\n    " + invalid.getKey() + "\n");

            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> loader.load(directory, null));

            assertEquals(invalid.getValue(), exception.propertyPath());
            assertEquals("app", exception.serviceName());
            assertEquals(composeFile, exception.sourceFile());
        }
    }

    @Test
    void acceptsCompositeHealthcheckDurationsIncludingStartInterval() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                    healthcheck:
                      interval: 1m30s
                      start_interval: 250ms
                """);

        var project = new ComposeFileLoader().load(directory, null);

        assertEquals("250ms", ((Map<?, ?>) project.services().get("app").configuration().get("healthcheck"))
                .get("start_interval"));
    }

    @Test
    void reportsOverlaySourceForInvalidServiceField() throws Exception {
        Path base = directory.resolve("compose.yaml");
        Path overlay = directory.resolve("compose.override.yaml");
        Files.writeString(base, "services:\n  app:\n    image: alpine\n");
        Files.writeString(overlay, "services:\n  app:\n    build: []\n");

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().loadMerged(directory,
                        List.of(base.getFileName().toString(), overlay.getFileName().toString())));

        assertEquals(overlay.toAbsolutePath().normalize(), exception.sourceFile());
        assertEquals("services.app.build", exception.propertyPath());
        assertEquals("app", exception.serviceName());
    }

    @Test
    void reportsOverlaySourceForInvalidNestedScalar() throws Exception {
        Path base = directory.resolve("compose.yaml");
        Path overlay = directory.resolve("compose.override.yaml");
        Files.writeString(base, "services:\n  app:\n    image: alpine\n    healthcheck:\n      interval: 30s\n");
        Files.writeString(overlay, "services:\n  app:\n    healthcheck:\n      interval: invalid\n");

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().loadMerged(directory,
                        List.of(base.getFileName().toString(), overlay.getFileName().toString())));

        assertEquals(overlay.toAbsolutePath().normalize(), exception.sourceFile());
        assertEquals("services.app.healthcheck.interval", exception.propertyPath());
    }

    @Test
    void rejectsNonScalarImageAndKeepsDottedServiceProvenanceIsolated() throws Exception {
        Path base = directory.resolve("compose.yaml");
        Path overlay = directory.resolve("compose.override.yaml");
        Files.writeString(base, "services:\n  api:\n    image: []\n");
        Files.writeString(overlay, "services:\n  api.image:\n    image: alpine\n");

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().loadMerged(directory,
                        List.of(base.getFileName().toString(), overlay.getFileName().toString())));

        assertEquals(base.toAbsolutePath().normalize(), exception.sourceFile());
        assertEquals("services.api.image", exception.propertyPath());
        assertEquals("api", exception.serviceName());
    }

    @Test
    void reportsBaseSourceForInvalidPortAfterOverlayAppend() throws Exception {
        Path base = directory.resolve("compose.yaml");
        Path overlay = directory.resolve("compose.override.yaml");
        Files.writeString(base, "services:\n  api:\n    image: alpine\n    ports: [invalid]\n");
        Files.writeString(overlay, "services:\n  api:\n    ports: [8080:80]\n");

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().loadMerged(directory,
                        List.of(base.getFileName().toString(), overlay.getFileName().toString())));

        assertEquals(base.toAbsolutePath().normalize(), exception.sourceFile());
        assertEquals("services.api.ports[0]", exception.propertyPath());
        assertEquals("api", exception.serviceName());
    }

    @Test
    void reportsOverlaySourceForInvalidAppendedPort() throws Exception {
        Path base = directory.resolve("compose.yaml");
        Path overlay = directory.resolve("compose.override.yaml");
        Files.writeString(base, "services:\n  api:\n    image: alpine\n    ports: [8080:80]\n");
        Files.writeString(overlay, "services:\n  api:\n    ports: [invalid]\n");

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().loadMerged(directory,
                        List.of(base.getFileName().toString(), overlay.getFileName().toString())));

        assertEquals(overlay.toAbsolutePath().normalize(), exception.sourceFile());
        assertEquals("services.api.ports[1]", exception.propertyPath());
        assertEquals("api", exception.serviceName());
    }

    @Test
    void acceptsComposePortRangesAndBracketedIpv6Mappings() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                    ports:
                      - "9090-9091:8080-8081"
                      - "[::1]:6001:6001/udp"
                      - "::1:6000:6000"
                """);

        var project = new ComposeFileLoader().load(directory, null);

        assertEquals(3, ((List<?>) project.services().get("app").configuration().get("ports")).size());
    }

    @Test
    void validatesLongPortMappingsAndReportsTheOverlayFieldSource() throws Exception {
        Path base = directory.resolve("compose.yaml");
        Path overlay = directory.resolve("compose.override.yaml");
        Files.writeString(base, "services:\n  app:\n    image: alpine\n");

        for (var invalid : List.of(
                Map.entry("target: 0", "services.app.ports[0].target"),
                Map.entry("target: 80\nprotocol: sctp", "services.app.ports[0].protocol"),
                Map.entry("target: 80\nhost_ip: localhost", "services.app.ports[0].host_ip"),
                Map.entry("target: 80\nmode: bridge", "services.app.ports[0].mode"),
                Map.entry("target: 80\ncontainer_port: 8080", "services.app.ports[0].container_port"))) {
            Files.writeString(overlay, "services:\n  app:\n    ports:\n      - "
                    + invalid.getKey().replace("\n", "\n        ") + "\n");

            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> new ComposeFileLoader().loadMerged(directory,
                            List.of(base.getFileName().toString(), overlay.getFileName().toString())));

            assertEquals(overlay.toAbsolutePath().normalize(), exception.sourceFile());
            assertEquals(invalid.getValue(), exception.propertyPath());
        }
    }

    @Test
    void rejectsMutuallyExclusiveNetworkModeAndNetworksWhileLoading() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                    network_mode: bridge
                    networks: [default]
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, null));

        assertEquals("services.app.networks", exception.propertyPath());
        assertEquals("app", exception.serviceName());
    }

    @Test
    void rejectsUndefinedServiceNetworksAndAllowsImplicitDefault() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                    networks: [missing]
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, null));

        assertEquals("services.app.networks.missing", exception.propertyPath());
        assertEquals("app", exception.serviceName());

        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                    networks: [default]
                """);
        assertEquals(List.of("default"), new ComposeFileLoader().load(directory, null)
                .services().get("app").configuration().get("networks"));
    }

    @Test
    void validatesServiceNetworksShapeAndMappingValues() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        for (String networks : List.of("bridge", "[1]", "{ frontend: false }", "{ 1: {} }")) {
            Files.writeString(composeFile, "services:\n  app:\n    image: alpine\n    networks: " + networks + "\n");

            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> new ComposeFileLoader().load(directory, null), networks);

            assertEquals(networks.equals("[1]") ? "services.app.networks.0"
                    : networks.contains("frontend") ? "services.app.networks.frontend"
                    : networks.contains("{ 1") ? "services.app.networks.1"
                    : "services.app.networks", exception.propertyPath());
            assertEquals("app", exception.serviceName());
        }

        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                    networks:
                      default: {}
                      frontend:
                networks:
                  frontend: {}
                """);
        Map<?, ?> configured = (Map<?, ?>) new ComposeFileLoader().load(directory, null)
                .services().get("app").configuration().get("networks");
        assertEquals(Map.of(), configured.get("default"));
        assertNull(configured.get("frontend"));
    }

    @Test
    void validatesMappedServiceNetworkOptionTypes() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        for (Map.Entry<String, String> invalid : Map.of(
                "aliases: app", "services.app.networks.frontend.aliases",
                "aliases: [1]", "services.app.networks.frontend.aliases.0",
                "ipv4_address: 4", "services.app.networks.frontend.ipv4_address",
                "ipv6_address: false", "services.app.networks.frontend.ipv6_address",
                "mac_address: 1", "services.app.networks.frontend.mac_address",
                "priority: invalid", "services.app.networks.frontend.priority",
                "priority: 1.5", "services.app.networks.frontend.priority").entrySet()) {
            Files.writeString(composeFile, "services:\n  app:\n    image: alpine\n    networks:\n      frontend:\n        "
                    + invalid.getKey() + "\nnetworks:\n  frontend: {}\n");

            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> new ComposeFileLoader().load(directory, null), invalid.getKey());

            assertEquals(invalid.getValue(), exception.propertyPath());
            assertEquals("app", exception.serviceName());
        }

        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                    networks:
                      frontend:
                        aliases: [web, api]
                        ipv4_address: "192.0.2.10"
                        ipv6_address: "2001:db8::10"
                        mac_address: "02:42:ac:11:00:02"
                        priority: 100
                networks:
                  frontend: {}
                """);
        Map<?, ?> options = (Map<?, ?>) ((Map<?, ?>) new ComposeFileLoader().load(directory, null)
                .services().get("app").configuration().get("networks")).get("frontend");
        assertEquals(List.of("web", "api"), options.get("aliases"));
        assertEquals("192.0.2.10", options.get("ipv4_address"));
        assertEquals("2001:db8::10", options.get("ipv6_address"));
        assertEquals("02:42:ac:11:00:02", options.get("mac_address"));
        assertEquals(100, options.get("priority"));
    }

    @Test
    void validatesServiceLinksAndAcceptsAliases() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                    links: [database:db]
                  database:
                    image: postgres
                """);

        assertEquals(List.of("database:db"), new ComposeFileLoader().load(directory, null)
                .services().get("app").configuration().get("links"));

        Files.writeString(composeFile, """
                services:
                  app:
                    image: alpine
                    links: [missing:db]
                """);
        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, null));

        assertEquals("services.app.links.0", exception.propertyPath());
        assertEquals("app", exception.serviceName());
    }

    @Test
    void retainsReservedDockerComposeLabelsForRuntimeValidation() throws Exception {
        Path composeFile = directory.resolve("compose.yaml");
        ComposeFileLoader loader = new ComposeFileLoader();

        Files.writeString(composeFile, """
                services:
                  app:
                    image: example/app
                    labels:
                      com.docker.compose.project: forged
                """);
        assertEquals(Map.of("com.docker.compose.project", "forged"),
                loader.load(directory, null).services().get("app").configuration().get("labels"));

        Files.writeString(composeFile, """
                services:
                  app:
                    image: example/app
                    labels:
                      - com.docker.compose.service=forged
                """);
        assertEquals(List.of("com.docker.compose.service=forged"),
                loader.load(directory, null).services().get("app").configuration().get("labels"));

        Files.writeString(composeFile, """
                services:
                  app:
                    image: example/app
                    labels:
                      com.example.component: api
                """);
        assertEquals(Map.of("com.example.component", "api"),
                loader.load(directory, null).services().get("app").configuration().get("labels"));
    }

    @Test
    void labelFilesMergeInOrderAndInlineLabelsTakePrecedence() throws Exception {
        Files.writeString(directory.resolve("base.labels"), "SHARED=base\nFILE_ONLY=one\n");
        Files.writeString(directory.resolve("override.labels"), "SHARED=override\nINTERPOLATED=${LABEL_VALUE}\n");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    label_file: [base.labels, override.labels]
                    labels:
                      SHARED: inline
                      INLINE: "yes"
                """);

        Map<?, ?> labels = (Map<?, ?>) new ComposeFileLoader().load(directory, null,
                Map.of("LABEL_VALUE", "resolved")).services().get("app").configuration().get("labels");

        assertEquals(Map.of("SHARED", "inline", "FILE_ONLY", "one", "INTERPOLATED", "resolved", "INLINE", "yes"), labels);
    }

    @Test
    void missingLabelFileFailsWithServicePropertyContext() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    label_file: missing.labels
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, null));

        assertEquals("services.app.label_file", exception.propertyPath());
        assertEquals("app", exception.serviceName());
    }

    @Test
    void inlineEnvironmentOverridesEnvFiles() throws Exception {
        Files.writeString(directory.resolve("app.env"), String.join("\n",
                "MODE=from-file",
                "FILE_ONLY=yes",
                "MULTILINE='first",
                "second'",
                "SINGLE='$JCOMPOSE_ENV_MISSING'",
                "DOUBLE=\"line\\nnext\"",
                "COLON: colon-value",
                ""));
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    env_file: app.env
                    environment:
                      MODE: inline
                """);

        var project = new ComposeFileLoader().load(directory, null);
        assertEquals(Map.of("MODE", "inline", "FILE_ONLY", "yes", "MULTILINE", "first\nsecond",
                        "SINGLE", "$JCOMPOSE_ENV_MISSING", "DOUBLE", "line\nnext", "COLON", "colon-value"),
                project.services().get("app").configuration().get("environment"));
    }

    @Test
    void envFileBareEntryPassesThroughProcessValueOrIsOmittedWhenUnset() throws Exception {
        String variable = "JC_ENV_FILE_BARE_" + java.util.UUID.randomUUID().toString().replace("-", "");
        Files.writeString(directory.resolve("app.env"), variable + "\n");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    env_file: app.env
                """);

        Map<?, ?> passedThrough = (Map<?, ?>) new ComposeFileLoader().load(directory, null,
                Map.of(variable, "from-process")).services().get("app").configuration().get("environment");
        Map<?, ?> unset = (Map<?, ?>) new ComposeFileLoader().load(directory, null)
                .services().get("app").configuration().get("environment");

        assertEquals("from-process", passedThrough.get(variable));
        org.junit.jupiter.api.Assertions.assertTrue(unset == null || !unset.containsKey(variable));
    }

    @Test
    void keyOnlyEnvironmentPassesThroughProcessValueAndOverridesEnvFile() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        String keyOnly = "JC_ENV_FILE_KEY_ONLY_" + suffix;
        String nullValue = "JC_ENV_FILE_NULL_" + suffix;
        Files.writeString(directory.resolve("app.env"), """
                %s=from-file
                %s=from-file
                """.formatted(keyOnly, nullValue));
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  map-form:
                    image: example/app
                    env_file: app.env
                    environment:
                      %s:
                      %s: null
                  list-form:
                    image: example/app
                    env_file: app.env
                    environment:
                      - %s
                """.formatted(keyOnly, nullValue, keyOnly));

        var loader = new ComposeFileLoader();
        Map<?, ?> mapped = (Map<?, ?>) loader.load(directory, null, Map.of(
                keyOnly, "from-host", nullValue, "host-null-value"))
                .services().get("map-form").configuration().get("environment");
        Map<?, ?> listed = (Map<?, ?>) loader.load(directory, null,
                Map.of(keyOnly, "from-host"))
                .services().get("list-form").configuration().get("environment");
        Map<?, ?> unresolved = (Map<?, ?>) loader.load(directory, null)
                .services().get("map-form").configuration().get("environment");

        assertEquals("from-host", mapped.get(keyOnly));
        assertEquals("host-null-value", mapped.get(nullValue));
        assertEquals("from-host", listed.get(keyOnly));
        org.junit.jupiter.api.Assertions.assertNull(unresolved.get(keyOnly));
    }

    @Test
    void interpolationAppliesToYamlValuesButNotKeysCommentsOrSingleQuotedScalars() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                # ${MISSING:?comments must not be interpolated}
                services:
                  app:
                    image: "example/${TAG}"
                    environment:
                      ${ENV_KEY}: literal-value
                    labels:
                      - "${LABEL_KEY}=interpolated-value"
                    command: '${TAG}'
                """);

        var project = new ComposeFileLoader().load(directory, null,
                Map.of("ENV_KEY", "literal-key", "TAG", "v1:blue", "LABEL_KEY", "resolved-label"));

        Map<?, ?> configuration = project.services().get("app").configuration();
        assertEquals("example/v1:blue", configuration.get("image"));
        assertEquals("${TAG}", configuration.get("command"));
        assertEquals(Map.of("${ENV_KEY}", "literal-value"), configuration.get("environment"));
        assertEquals(List.of("resolved-label=interpolated-value"), configuration.get("labels"));
    }

    @Test
    void projectEnvSupportsComposeDelimitersQuotesEscapesAndMultilineValues() throws Exception {
        Files.writeString(directory.resolve(".env"), String.join("\n",
                "JCOMPOSE_ENV_COLON: value",
                "JCOMPOSE_ENV_SINGLE='$JCOMPOSE_ENV_MISSING'",
                "JCOMPOSE_ENV_MULTI='first",
                "second'",
                "JCOMPOSE_ENV_SINGLE_ESCAPE='Let\\'s go!'",
                "JCOMPOSE_ENV_DOUBLE=\"line\\nnext\"",
                "JCOMPOSE_ENV_REFERENCE=${JCOMPOSE_ENV_COLON}-suffix",
                "JCOMPOSE_ENV_REFERENCE_CHAIN=${JCOMPOSE_ENV_REFERENCE}-next",
                "JCOMPOSE_ENV_FORWARD=${JCOMPOSE_ENV_LATER}",
                "JCOMPOSE_ENV_LATER=late",
                "JCOMPOSE_ENV_HASH=hash#value",
                "JCOMPOSE_ENV_QUOTED_HASH=\"hash # value\" # comment",
                "JCOMPOSE_ENV_HASH_WITHOUT_SPACE=value#not-a-comment",
                "JCOMPOSE_ENV_COMMENT=value # comment",
                "JCOMPOSE_ENV_EMPTY=",
                "JCOMPOSE_ENV_UNSET",
                ""));
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: "example/${JCOMPOSE_ENV_COLON}"
                    environment:
                      SINGLE: "${JCOMPOSE_ENV_SINGLE}"
                      MULTI: "${JCOMPOSE_ENV_MULTI}"
                      SINGLE_ESCAPE: "${JCOMPOSE_ENV_SINGLE_ESCAPE}"
                      DOUBLE: "${JCOMPOSE_ENV_DOUBLE}"
                      REFERENCE: "${JCOMPOSE_ENV_REFERENCE}"
                      REFERENCE_CHAIN: "${JCOMPOSE_ENV_REFERENCE_CHAIN}"
                      FORWARD: "${JCOMPOSE_ENV_FORWARD}"
                      HASH: "${JCOMPOSE_ENV_HASH}"
                      QUOTED_HASH: "${JCOMPOSE_ENV_QUOTED_HASH}"
                      HASH_WITHOUT_SPACE: "${JCOMPOSE_ENV_HASH_WITHOUT_SPACE}"
                      COMMENT: "${JCOMPOSE_ENV_COMMENT}"
                      EMPTY: "${JCOMPOSE_ENV_EMPTY:-empty-default}"
                      UNSET: "${JCOMPOSE_ENV_UNSET:-unset-default}"
                """);

        var project = new ComposeFileLoader().load(directory, null);
        Map<?, ?> environment = (Map<?, ?>) project.services().get("app").configuration().get("environment");
        assertEquals("example/value", project.services().get("app").configuration().get("image"));
        assertEquals("$JCOMPOSE_ENV_MISSING", environment.get("SINGLE"));
        assertEquals("first\nsecond", environment.get("MULTI"));
        assertEquals("Let's go!", environment.get("SINGLE_ESCAPE"));
        assertEquals("line\nnext", environment.get("DOUBLE"));
        assertEquals("value-suffix", environment.get("REFERENCE"));
        assertEquals("value-suffix-next", environment.get("REFERENCE_CHAIN"));
        assertEquals("", environment.get("FORWARD"));
        assertEquals("hash#value", environment.get("HASH"));
        assertEquals("hash # value", environment.get("QUOTED_HASH"));
        assertEquals("value#not-a-comment", environment.get("HASH_WITHOUT_SPACE"));
        assertEquals("value", environment.get("COMMENT"));
        assertEquals("empty-default", environment.get("EMPTY"));
        assertEquals("unset-default", environment.get("UNSET"));
    }

    @Test
    void overlaysAppendUniqueListsAndMergeKeyedEntriesAndMountTargets() throws Exception {
        Files.writeString(directory.resolve("base.yaml"), """
                services:
                  app:
                    image: example/app
                    command: ["base"]
                    ports: ["8080:80", "9000:90"]
                    environment: ["MODE=base", "KEEP=yes"]
                    volumes: ["./old:/data", "./logs:/logs"]
                """);
        Files.writeString(directory.resolve("override.yaml"), """
                services:
                  app:
                    command: ["override"]
                    ports: ["9000:90", "9443:443"]
                    environment: ["MODE=override", "NEW=yes"]
                    volumes: ["./new:/data"]
                """);

        var project = new ComposeFileLoader().loadMerged(directory, List.of("base.yaml", "override.yaml"));
        Map<?, ?> app = project.services().get("app").configuration();
        assertEquals(List.of("override"), app.get("command"));
        assertEquals(List.of("8080:80", "9000:90", "9443:443"), app.get("ports"));
        assertEquals(List.of("MODE=override", "KEEP=yes", "NEW=yes"), app.get("environment"));
        assertEquals(List.of("./new:/data", "./logs:/logs"), app.get("volumes"));
    }

    @Test
    void overlayMergesDeduplicateComposeServiceSequenceFields() throws Exception {
        Files.writeString(directory.resolve("base.yaml"), """
                services:
                  app:
                    image: example/app
                    cap_add: [NET_ADMIN, SYS_TIME]
                    cap_drop: [MKNOD]
                    device_cgroup_rules: ["c 1:3 mr"]
                    expose: [8080, 9090]
                    external_links: [db, "cache:redis"]
                    security_opt: [no-new-privileges:true]
                    deploy:
                      placement:
                        constraints: [node.platform.os == linux]
                        preferences: ["spread: node.labels.zone"]
                      reservations:
                        generic_resources: [{discrete_resource_spec: {kind: gpu, value: 1}}]
                """);
        Files.writeString(directory.resolve("override.yaml"), """
                services:
                  app:
                    cap_add: [SYS_TIME, SYS_PTRACE, SYS_PTRACE]
                    cap_drop: [MKNOD, SYS_ADMIN]
                    device_cgroup_rules: ["c 1:3 mr", "c 1:5 rwm"]
                    expose: [9090, 7070]
                    external_links: ["cache:redis", "legacy:legacy"]
                    security_opt: [no-new-privileges:true, seccomp:unconfined]
                    deploy:
                      placement:
                        constraints: [node.platform.os == linux, node.arch == amd64]
                        preferences: ["spread: node.labels.zone"]
                      reservations:
                        generic_resources: [{discrete_resource_spec: {kind: gpu, value: 1}}]
                """);

        var project = new ComposeFileLoader().loadMerged(directory, List.of("base.yaml", "override.yaml"));
        Map<?, ?> app = project.services().get("app").configuration();
        assertEquals(List.of("NET_ADMIN", "SYS_TIME", "SYS_PTRACE"), app.get("cap_add"));
        assertEquals(List.of("MKNOD", "SYS_ADMIN"), app.get("cap_drop"));
        assertEquals(List.of("c 1:3 mr", "c 1:5 rwm"), app.get("device_cgroup_rules"));
        assertEquals(List.of(8080, 9090, 7070), app.get("expose"));
        assertEquals(List.of("db", "cache:redis", "legacy:legacy"), app.get("external_links"));
        assertEquals(List.of("no-new-privileges:true", "seccomp:unconfined"), app.get("security_opt"));

        Map<?, ?> deploy = (Map<?, ?>) app.get("deploy");
        Map<?, ?> placement = (Map<?, ?>) deploy.get("placement");
        assertEquals(List.of("node.platform.os == linux", "node.arch == amd64"), placement.get("constraints"));
        assertEquals(List.of("spread: node.labels.zone"), placement.get("preferences"));
        Map<?, ?> reservations = (Map<?, ?>) deploy.get("reservations");
        assertEquals(1, ((List<?>) reservations.get("generic_resources")).size());
    }

    @Test
    void externalLinksRequireContainerNamesAndOptionalAliases() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    external_links: ["legacy:"]
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, "compose.yaml"));
        assertEquals("services.app.external_links.0", exception.propertyPath());
    }

    @Test
    void portsMergeAcrossShortAndLongSyntaxByComposeUniqueKey() throws Exception {
        Files.writeString(directory.resolve("base.yaml"), """
                services:
                  app:
                    image: example/app
                    ports: ["8080:80", "8081:80"]
                """);
        Files.writeString(directory.resolve("override.yaml"), """
                services:
                  app:
                    ports:
                      - target: 80
                        published: "8080"
                        protocol: tcp
                      - target: 80
                        published: "8082"
                        protocol: tcp
                """);

        var project = new ComposeFileLoader().loadMerged(directory, List.of("base.yaml", "override.yaml"));
        Map<?, ?> app = project.services().get("app").configuration();

        assertEquals(List.of(
                Map.of("target", 80, "published", "8080", "protocol", "tcp"),
                "8081:80",
                Map.of("target", 80, "published", "8082", "protocol", "tcp")), app.get("ports"));
    }

    @Test
    void portRangesMergeAcrossShortAndLongSyntaxByComposeUniqueKey() throws Exception {
        Files.writeString(directory.resolve("base.yaml"), """
                services:
                  app:
                    image: example/app
                    ports: ["8080-8081:80-81"]
                """);
        Files.writeString(directory.resolve("override.yaml"), """
                services:
                  app:
                    ports:
                      - target: "80-81"
                        published: "8080-8081"
                        protocol: tcp
                """);

        var project = new ComposeFileLoader().loadMerged(directory, List.of("base.yaml", "override.yaml"));
        Map<?, ?> app = project.services().get("app").configuration();

        assertEquals(List.of(Map.of("target", "80-81", "published", "8080-8081", "protocol", "tcp")),
                app.get("ports"));
    }

    @Test
    void unbracketedIpv6ShortPortsMergeWithLongSyntaxByComposeUniqueKey() throws Exception {
        Files.writeString(directory.resolve("base.yaml"), """
                services:
                  app:
                    image: example/app
                    ports: ["::1:6000:6000"]
                """);
        Files.writeString(directory.resolve("override.yaml"), """
                services:
                  app:
                    ports:
                      - target: 6000
                        published: "6000"
                        host_ip: "::1"
                        protocol: tcp
                """);

        var project = new ComposeFileLoader().loadMerged(directory, List.of("base.yaml", "override.yaml"));
        Map<?, ?> app = project.services().get("app").configuration();

        assertEquals(List.of(Map.of("target", 6000, "published", "6000", "host_ip", "::1", "protocol", "tcp")),
                app.get("ports"));
    }

    @Test
    void extendsServiceInSameFileAndMergesOverridesByComposeRules() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  base:
                    image: example/base
                    command: ["base"]
                    environment:
                      MODE: base
                      KEEP: "yes"
                    volumes: ["base-data:/data", "base-log:/logs"]
                    cap_add: [CHOWN, SYS_ADMIN]
                    expose: [80, 443]
                  app:
                    extends:
                      service: base
                    command: ["app"]
                    environment:
                      MODE: app
                    volumes: ["app-data:/data"]
                    cap_add: [SYS_ADMIN, NET_ADMIN]
                    expose: [443, 8080]
                volumes:
                  base-data: {}
                  base-log: {}
                  app-data: {}
                """);

        Map<?, ?> app = new ComposeFileLoader().load(directory, null).services().get("app").configuration();

        assertEquals("example/base", app.get("image"));
        assertEquals(List.of("base", "app"), app.get("command"));
        assertEquals(Map.of("MODE", "app", "KEEP", "yes"), app.get("environment"));
        assertEquals(List.of("app-data:/data", "base-log:/logs"), app.get("volumes"));
        assertEquals(List.of("CHOWN", "SYS_ADMIN", "NET_ADMIN"), app.get("cap_add"));
        assertEquals(List.of(80, 443, 8080), app.get("expose"));
        org.junit.jupiter.api.Assertions.assertFalse(app.containsKey("extends"));
    }

    @Test
    void extendsExternalServiceAndRebasesItsRelativePathsToProjectFile() throws Exception {
        Path common = Files.createDirectories(directory.resolve("common"));
        Files.writeString(common.resolve("app.env"), "FROM_EXTENDS=yes\n");
        Files.writeString(common.resolve("compose.yaml"), """
                services:
                  base:
                    image: example/base
                    build:
                      context: ./context
                    env_file: app.env
                    volumes: ["./data:/data:ro"]
                """);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    extends:
                      file: common/compose.yaml
                      service: base
                    command: ["start"]
                """);

        Map<?, ?> app = new ComposeFileLoader().load(directory, null).services().get("app").configuration();

        assertEquals(Map.of("context", "common/context"), app.get("build"));
        assertEquals("common/app.env", app.get("env_file"));
        assertEquals(List.of("./common/data:/data:ro"), app.get("volumes"));
        assertEquals(Map.of("FROM_EXTENDS", "yes"), app.get("environment"));
    }

    @Test
    void rejectsCircularExtendsReferences() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  first:
                    extends:
                      service: second
                  second:
                    extends:
                      service: first
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, null));

        assertEquals("services.first.extends", exception.propertyPath());
    }

    @Test
    void rejectsEnablingHealthcheckDisableOnlyInExtendingService() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  base:
                    image: example/base
                  app:
                    extends:
                      service: base
                    healthcheck:
                      disable: true
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, null));

        assertEquals("services.app.healthcheck.disable", exception.propertyPath());
    }

    @Test
    void allowsExtendedHealthcheckDisableWhenBaseAlsoDisablesIt() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  base:
                    image: example/base
                    healthcheck:
                      disable: true
                  app:
                    extends:
                      service: base
                    healthcheck:
                      disable: true
                """);

        Map<?, ?> app = new ComposeFileLoader().load(directory, null).services().get("app").configuration();

        assertEquals(Map.of("disable", true), app.get("healthcheck"));
    }
}
