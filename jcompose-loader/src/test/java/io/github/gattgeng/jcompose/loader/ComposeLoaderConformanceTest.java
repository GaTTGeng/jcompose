package io.github.gattgeng.jcompose.loader;

import io.github.gattgeng.jcompose.api.ComposeProject;
import io.github.gattgeng.jcompose.api.ComposeServicePort;
import io.github.gattgeng.jcompose.api.ComposeValidationException;
import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Maps the ComposeSharp loader conformance fixtures to jcompose's normalized raw model. */
class ComposeLoaderConformanceTest {
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsMissingOrEmptyServicesLikeDockerCompose() throws IOException {
        Path missingServices = temporaryDirectory.resolve("missing-services.yaml");
        Files.writeString(missingServices, "name: empty-project\n");
        assertTrue(new ComposeFileLoader().load(temporaryDirectory, "missing-services.yaml").services().isEmpty());

        Path emptyServices = temporaryDirectory.resolve("empty-services.yaml");
        Files.writeString(emptyServices, "services: {}\n");
        assertTrue(new ComposeFileLoader().load(temporaryDirectory, "empty-services.yaml").services().isEmpty());
    }

    @Test
    void rejectsNonMappingServicesSection() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, "services: null\n");

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));

        assertEquals("services", exception.propertyPath());
    }

    @Test
    void interpolatesSingleQuotedAndBlockScalarsLikeDockerCompose() throws IOException {
        String variable = "JCOMPOSE_BLOCK_SCALAR_INTERPOLATION";
        Files.writeString(temporaryDirectory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    command: |
                      echo ${JCOMPOSE_BLOCK_SCALAR_INTERPOLATION:-literal-default}
                    entrypoint: >-
                      echo ${JCOMPOSE_BLOCK_SCALAR_INTERPOLATION:-folded-default}
                    environment:
                      BLOCK_VALUE: |
                        ${JCOMPOSE_BLOCK_SCALAR_INTERPOLATION:-block-default}
                      LITERAL_VALUE: '${JCOMPOSE_BLOCK_SCALAR_INTERPOLATION:-single-quoted}'
                """);

        ServiceDefinition app = new ComposeFileLoader().load(temporaryDirectory, "compose.yaml", Map.of(variable, ""))
                .services().get("app");
        Map<?, ?> environment = (Map<?, ?>) app.configuration().get("environment");

        assertEquals("echo literal-default\n", app.configuration().get("command"));
        assertEquals("echo folded-default", app.configuration().get("entrypoint"));
        assertEquals("block-default\n", environment.get("BLOCK_VALUE"));
        assertEquals("single-quoted", environment.get("LITERAL_VALUE"));
    }

    @Test
    void rejectsNonStringTopLevelProjectName() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                name:
                  nested: value
                services:
                  app:
                    image: busybox
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));

        assertEquals("name", exception.propertyPath());
    }

    @Test
    void mapsBuildDefinitionFixture() throws IOException {
        ComposeProject project = loadFixture("build-definition");
        ServiceDefinition app = project.services().get("app");
        Map<?, ?> build = (Map<?, ?>) app.build();

        assertEquals("build-definition", project.sourceFile().getParent().getFileName().toString());
        assertEquals("example/app:build", app.image());
        assertEquals("./app", build.get("context"));
        assertEquals("Dockerfile.test", build.get("dockerfile"));
        assertEquals("test", ((Map<?, ?>) build.get("args")).get("BUILD_MODE"));
        assertEquals("runtime", build.get("target"));
        assertEquals(List.of("example/app:test"), build.get("tags"));
        assertEquals("enabled", ((Map<?, ?>) build.get("labels")).get("build.label"));
        assertEquals(List.of("linux/amd64"), build.get("platforms"));
        assertTrue((Boolean) build.get("pull"));
        assertTrue((Boolean) build.get("no_cache"));
    }

    @Test
    void acceptsInlineDockerfileAndRejectsDockerfilePathCombination() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    build:
                      context: .
                      dockerfile_inline: |-
                        FROM scratch
                """);

        Map<?, ?> build = (Map<?, ?>) new ComposeFileLoader().load(temporaryDirectory, "compose.yaml")
                .services().get("app").build();
        assertEquals("FROM scratch", build.get("dockerfile_inline"));

        Files.writeString(file, """
                services:
                  app:
                    build:
                      context: .
                      dockerfile: Dockerfile
                      dockerfile_inline: FROM scratch
                """);
        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));
        assertEquals("services.app.build.dockerfile_inline", exception.propertyPath());
    }

    @Test
    void rejectsNonBooleanBindCreateHostPath() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    volumes:
                      - type: bind
                        source: ./data
                        target: /data
                        bind:
                          create_host_path: sometimes
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));

        assertEquals("services.app.volumes.0.bind.create_host_path", exception.propertyPath());
    }

    @Test
    void rejectsInvalidLongSyntaxTmpfsSize() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    volumes:
                      - type: tmpfs
                        target: /run/cache
                        tmpfs:
                          size: tiny
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));

        assertEquals("services.app.volumes.0.tmpfs.size", exception.propertyPath());
    }

    @Test
    void rejectsNonOctalLongSyntaxTmpfsMode() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    volumes:
                      - type: tmpfs
                        target: /run/cache
                        tmpfs:
                          mode: "0899"
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));

        assertEquals("services.app.volumes.0.tmpfs.mode", exception.propertyPath());
    }

    @Test
    void rejectsUnsupportedUtsMode() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    uts: container:other
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));

        assertEquals("services.app.uts", exception.propertyPath());
    }

    @Test
    void malformedInterpolationIncludesComposePropertyAndSourceLocation() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: "${IMAGE:invalid}"
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));

        assertEquals("services.app.image", exception.propertyPath());
        assertEquals("app", exception.serviceName());
        assertEquals(file.toAbsolutePath(), exception.sourceFile());
        assertTrue(exception.line() != null);
    }

    @Test
    void rejectsNonMappingLongSyntaxTmpfsOptions() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    volumes:
                      - type: tmpfs
                        target: /run/cache
                        tmpfs: invalid
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));

        assertEquals("services.app.volumes.0.tmpfs", exception.propertyPath());
    }

    @Test
    void rejectsUndefinedServiceSecret() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    secrets: [missing]
                """);

        assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));
    }

    @Test
    void validatesServiceNameCharacters() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        ComposeFileLoader loader = new ComposeFileLoader();
        for (String name : List.of("bad name", "foo:bar", "abc@x", "abc/x", "ümlaut")) {
            Files.writeString(file, "services:\n  \"" + name + "\":\n    image: busybox\n");
            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> loader.load(temporaryDirectory, "compose.yaml"), name);
            assertEquals("services." + name, exception.propertyPath());
            assertEquals(name, exception.serviceName());
        }

        for (String name : List.of("a", "UPPER", "my_service", ".hidden", "1abc", "_abc", "-abc", "a..b")) {
            Files.writeString(file, "services:\n  \"" + name + "\":\n    image: busybox\n");
            assertTrue(loader.load(temporaryDirectory, "compose.yaml").services().containsKey(name), name);
        }
    }

    @Test
    void rejectsUndefinedServiceDependency() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    depends_on: [missing]
                """);

        assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));
    }

    @Test
    void acceptsUndefinedOptionalServiceDependency() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    depends_on:
                      optional-helper:
                        condition: service_started
                        required: false
                """);

        ComposeProject project = new ComposeFileLoader().load(temporaryDirectory, "compose.yaml");

        assertTrue(project.services().containsKey("app"));
    }

    @Test
    void validatesDependencySyntaxAndLongForm() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        ComposeFileLoader loader = new ComposeFileLoader();
        for (String dependency : List.of(" db", " null", " [1]", "\n      db: true",
                "\n      db:\n        condition: unsupported", "\n      db:\n        restart: true",
                "\n      db:\n        condition: service_started\n        mystery: true")) {
            Files.writeString(file, "services:\n  app:\n    image: busybox\n    depends_on:"
                    + dependency + "\n  db:\n    image: busybox\n");
            assertThrows(ComposeValidationException.class,
                    () -> loader.load(temporaryDirectory, "compose.yaml"), dependency);
        }

        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    depends_on:
                      db:
                        condition: service_healthy
                        restart: true
                        required: false
                  db:
                    image: busybox
                """);
        assertTrue(loader.load(temporaryDirectory, "compose.yaml").services().containsKey("app"));
    }

    @Test
    void rejectsUndefinedNamedServiceVolume() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    volumes: [missing:/data]
                """);

        assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));
    }

    @Test
    void rejectsUndefinedServiceConfig() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    configs:
                      - source: missing
                """);

        assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));
    }

    @Test
    void resolvesCommandAndEnvironmentFixture() throws IOException {
        ServiceDefinition app = loadFixture("command-environment").services().get("app");

        assertEquals("example/app:1.2.3", app.image());
        assertEquals(List.of("dotnet", "app.dll"), app.configuration().get("command"));
        assertEquals(List.of("/bin/sh", "-c"), app.configuration().get("entrypoint"));
        Map<?, ?> environment = (Map<?, ?>) app.configuration().get("environment");
        assertEquals("Server=database;Database=app", environment.get("CONNECTION_STRING"));
        assertEquals("Testing", environment.get("ASPNETCORE_ENVIRONMENT"));
        assertEquals("enabled", environment.get("FEATURE_FLAG"));
    }

    @Test
    void mapsNetworkAndStorageFixture() throws IOException {
        ComposeProject project = loadFixture("network-storage");
        ServiceDefinition app = project.services().get("app");

        assertEquals(List.of("app-data"), project.volumes());
        assertEquals(List.of("frontend"), project.networks());
        assertEquals(List.of("127.0.0.1:8080:8081/tcp", "9090:9091/udp"), app.configuration().get("ports"));
        assertEquals(List.of(
                new ComposeServicePort("8081", "8080", "127.0.0.1", "tcp", null, null, null),
                new ComposeServicePort("9091", "9090", null, "udp", null, null, null)), app.ports());
        assertEquals(List.of("app-data:/var/lib/app"), app.configuration().get("volumes"));
        assertEquals(List.of("frontend"), app.configuration().get("networks"));
        assertEquals("api", ((Map<?, ?>) app.configuration().get("labels")).get("com.example.component"));
    }

    @Test
    void exposesLongSyntaxPortsAsTypedMappings() throws IOException {
        Path compose = temporaryDirectory.resolve("long-ports.yaml");
        Files.writeString(compose, "services:\n  app:\n    image: example/app\n    ports:\n      - target: 8080\n        published: \"8443\"\n        host_ip: 127.0.0.1\n        protocol: tcp\n        mode: host\n        name: https\n        app_protocol: https\n");

        ServiceDefinition app = new ComposeFileLoader().load(temporaryDirectory, "long-ports.yaml")
                .services().get("app");

        assertEquals(List.of(new ComposeServicePort(
                "8080", "8443", "127.0.0.1", "tcp", "host", "https", "https")), app.ports());
    }

    @Test
    void mapsServiceLifecycleFixture() throws IOException {
        ServiceDefinition app = loadFixture("service-lifecycle").services().get("app");
        Map<?, ?> healthcheck = (Map<?, ?>) app.configuration().get("healthcheck");

        assertEquals("on-failure:4", app.configuration().get("restart"));
        assertEquals(List.of("debug", "tests"), app.profiles());
        assertEquals(List.of("CMD-SHELL", "curl --fail http://localhost:8081/health || exit 1"), healthcheck.get("test"));
        assertEquals("30s", healthcheck.get("interval"));
        assertEquals("5s", healthcheck.get("timeout"));
        assertEquals(3, healthcheck.get("retries"));
        assertEquals("10s", healthcheck.get("start_period"));
    }

    @Test
    void validatesProfileNameFormatDuringLoading() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        for (String profile : List.of("debug", "1test", "test.profile", "test-profile", "test_profile")) {
            Files.writeString(file, "services:\n  app:\n    image: busybox\n    profiles: [\"" + profile + "\"]\n");
            assertEquals(List.of(profile), new ComposeFileLoader().load(temporaryDirectory, "compose.yaml")
                    .services().get("app").profiles());
        }

        for (String profile : List.of("", "x", "-test", ".test", "_test", "test/profile", "测试")) {
            Files.writeString(file, "services:\n  app:\n    image: busybox\n    profiles: [\"" + profile + "\"]\n");
            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));
            assertEquals("services.app.profiles.0", exception.propertyPath());
        }
    }

    @Test
    void validatesContainerNameFormatDuringLoading() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        for (String name : List.of("app-container", "1app", "app.container", "app_container")) {
            Files.writeString(file, "services:\n  app:\n    image: busybox\n    container_name: \"" + name + "\"\n");
            assertEquals(name, new ComposeFileLoader().load(temporaryDirectory, "compose.yaml")
                    .services().get("app").configuration().get("container_name"));
        }

        for (String name : List.of("", "x", "-app", ".app", "_app", "app/name", "ümlaut")) {
            Files.writeString(file, "services:\n  app:\n    image: busybox\n    container_name: \"" + name + "\"\n");
            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));
            assertEquals("services.app.container_name", exception.propertyPath());
        }
    }

    @Test
    void validatesServiceRestartPolicyDuringLoading() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        for (String restart : List.of("no", "always", "unless-stopped", "on-failure", "on-failure:0", "on-failure:3")) {
            Files.writeString(file, "services:\n  app:\n    image: busybox\n    restart: \"" + restart + "\"\n");
            assertEquals(restart, new ComposeFileLoader().load(temporaryDirectory, "compose.yaml")
                    .services().get("app").configuration().get("restart"));
        }

        for (String restart : List.of("sometimes", "always:2", "on-failure:", "on-failure:-1",
                "on-failure:many", "on-failure:2147483648")) {
            Files.writeString(file, "services:\n  app:\n    image: busybox\n    restart: \"" + restart + "\"\n");
            ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                    () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));
            assertEquals("services.app.restart", exception.propertyPath());
        }
    }

    @Test
    void validatesComposeDeployRestartPolicyMaxAttempts() throws IOException {
        Path file = temporaryDirectory.resolve("compose.yaml");
        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    deploy:
                      restart_policy:
                        condition: on-failure
                        max_attempts: 3
                """);
        var project = new ComposeFileLoader().load(temporaryDirectory, "compose.yaml");
        assertEquals(3, project.services().get("app").deployConfig().restartPolicy().maxRetries());

        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    deploy:
                      restart_policy:
                        max_attempts: -1
                """);
        ComposeValidationException invalidAttempts = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(temporaryDirectory, "compose.yaml"));
        assertEquals("services.app.deploy.restart_policy.max_attempts", invalidAttempts.propertyPath());

        Files.writeString(file, """
                services:
                  app:
                    image: busybox
                    deploy:
                      restart_policy:
                        max_retries: 3
                """);
        assertTrue(new ComposeFileLoader().load(temporaryDirectory, "compose.yaml").diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.propertyPath().equals("services.app.deploy.restart_policy.max_retries")
                        && diagnostic.code().equals("unsupported-field")));
    }

    @Test
    void mapsProjectResourcesFixture() throws IOException {
        ComposeProject project = loadFixture("service-resources");
        ServiceDefinition app = project.services().get("app");
        Map<?, ?> deploy = (Map<?, ?>) app.configuration().get("deploy");
        Map<?, ?> resources = (Map<?, ?>) deploy.get("resources");
        Map<?, ?> limits = (Map<?, ?>) resources.get("limits");
        Map<?, ?> reservations = (Map<?, ?>) resources.get("reservations");

        assertEquals(List.of("app-secret"), project.secrets());
        assertEquals(List.of("app-config"), project.configs());
        assertEquals(List.of("app-secret"), app.configuration().get("secrets"));
        assertEquals(List.of("app-config"), app.configuration().get("configs"));
        assertEquals(2, deploy.get("replicas"));
        assertEquals("512M", limits.get("memory"));
        assertEquals(2, limits.get("cpus"));
        assertEquals("128M", reservations.get("memory"));
    }

    @Test
    void preservesServiceDeclarationOrder() throws IOException {
        Files.writeString(temporaryDirectory.resolve("compose.yaml"), """
                services:
                  zulu:
                    image: example/zulu
                  alpha:
                    image: example/alpha
                """);

        ComposeProject project = new ComposeFileLoader().load(temporaryDirectory, "compose.yaml");

        assertEquals(List.of("zulu", "alpha"), List.copyOf(project.services().keySet()));
    }

    @Test
    void mergesOverrideFixtureFieldsAndResources() throws IOException {
        Path directory = copyFixture("merged-loader");
        ComposeProject project = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml"));
        ServiceDefinition app = project.services().get("app");

        assertEquals("example/app:override", app.image());
        assertEquals(List.of("run", "--verbose"), app.configuration().get("command"));
        Map<?, ?> environment = (Map<?, ?>) app.configuration().get("environment");
        assertEquals("base", environment.get("BASE_ONLY"));
        assertEquals("override", environment.get("SHARED"));
        assertEquals("overlay", environment.get("OVERLAY_ONLY"));
        assertEquals("frontend", ((Map<?, ?>) app.configuration().get("labels")).get("com.example.tier"));
        assertEquals("api", ((Map<?, ?>) app.configuration().get("labels")).get("com.example.component"));
        assertEquals(List.of("8080:80", "9090:81"), app.configuration().get("ports"));
        assertEquals(List.of("override-data:/var/lib/app", "cache:/cache", "logs:/var/log/app"),
                app.configuration().get("volumes"));
        assertEquals(Map.of("frontend", Map.of(), "backend", Map.of()), app.configuration().get("networks"));
        assertTrue(project.services().containsKey("worker"));
        assertEquals(List.of("app-data", "cache", "override-data", "logs"), project.volumes());
        assertEquals(List.of("frontend", "backend"), project.networks());
        assertEquals(List.of("app-config", "app-config-override"), project.configs());
        assertEquals(List.of("app-secret", "app-secret-override"), project.secrets());
    }

    @Test
    void deduplicatesComposeUniqueOverlaySequences() throws IOException {
        Path directory = temporaryDirectory.resolve("overlay-sequences");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    build:
                      context: .
                      tags: [example/app:dev]
                    cap_add: [SYS_ADMIN, CHOWN]
                    devices: [/dev/null:/dev/x:rwm]
                    dns_opt: [ndots:5, timeout:1]
                    expose: [80, 443]
                    extra_hosts: [cache.local:127.0.0.1]
                    gpus:
                      - driver: nvidia
                        count: 1
                        capabilities: [gpu]
                    group_add: ["1000", "1001"]
                    links: [cache:cache]
                    profiles: [debug]
                    security_opt: [label=role:ROLE]
                    volumes_from: [cache]
                  cache:
                    image: example/cache
                """);
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    build:
                      tags: [example/app:dev]
                    cap_add: [CHOWN, NET_ADMIN]
                    devices: [/dev/null:/dev/x:rwm]
                    dns_opt: [timeout:1, attempts:2]
                    expose: [443, 8080]
                    extra_hosts: [cache.local:127.0.0.1]
                    gpus:
                      - driver: nvidia
                        count: 1
                        capabilities: [gpu]
                    group_add: ["1001", "1002"]
                    links: [cache:cache]
                    profiles: [debug]
                    security_opt: [label=role:ROLE, no-new-privileges:true]
                    volumes_from: [cache]
                """);

        ServiceDefinition app = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml")).services().get("app");
        Map<?, ?> build = (Map<?, ?>) app.configuration().get("build");

        assertEquals(List.of("example/app:dev"), build.get("tags"));
        assertEquals(List.of("SYS_ADMIN", "CHOWN", "NET_ADMIN"), app.configuration().get("cap_add"));
        assertEquals(List.of("/dev/null:/dev/x:rwm"), app.configuration().get("devices"));
        assertEquals(List.of("ndots:5", "timeout:1", "attempts:2"), app.configuration().get("dns_opt"));
        assertEquals(List.of(80, 443, 8080), app.configuration().get("expose"));
        assertEquals(List.of("cache.local:127.0.0.1"), app.configuration().get("extra_hosts"));
        assertEquals(List.of(Map.of("driver", "nvidia", "count", 1, "capabilities", List.of("gpu"))),
                app.configuration().get("gpus"));
        assertEquals(List.of("1000", "1001", "1002"), app.configuration().get("group_add"));
        assertEquals(List.of("cache:cache"), app.configuration().get("links"));
        assertEquals(List.of("debug"), app.configuration().get("profiles"));
        assertEquals(List.of("label=role:ROLE", "no-new-privileges:true"),
                app.configuration().get("security_opt"));
        assertEquals(List.of("cache"), app.configuration().get("volumes_from"));
    }

    @Test
    void removesDuplicateDnsAndTmpfsEntriesWhenMergingComposeFiles() throws IOException {
        Path directory = temporaryDirectory.resolve("overlay-dns-tmpfs");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    dns: [1.1.1.1, 8.8.8.8]
                    dns_search: [example.test, internal.test]
                    tmpfs: [/tmp, /run]
                """);
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    dns: [8.8.8.8, 9.9.9.9]
                    dns_search: [internal.test, staging.test]
                    tmpfs: [/run, /dev/shm]
                """);

        ServiceDefinition app = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml")).services().get("app");

        assertEquals(List.of("1.1.1.1", "8.8.8.8", "9.9.9.9"), app.configuration().get("dns"));
        assertEquals(List.of("example.test", "internal.test", "staging.test"),
                app.configuration().get("dns_search"));
        assertEquals(List.of("/tmp", "/run", "/dev/shm"), app.configuration().get("tmpfs"));
    }

    @Test
    void mergesListFormBuildArgsSysctlsAndAnnotationsByKey() throws IOException {
        Path directory = temporaryDirectory.resolve("overlay-keyed-lists");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    build:
                      context: .
                      args: [APP_MODE=base, LOG_LEVEL=info]
                    sysctls: [net.ipv4.ip_forward=0, kernel.shm_rmid_forced=0]
                    annotations: [com.example.mode=base, com.example.owner=team-a]
                    extra_hosts: [service.local:127.0.0.1]
                """);
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    build:
                      args: [APP_MODE=release, DEBUG=0]
                    sysctls: [net.ipv4.ip_forward=1, kernel.threads-max=4096]
                    annotations: [com.example.mode=release, com.example.owner=team-a]
                    extra_hosts: [service.local:127.0.0.2]
                """);

        ServiceDefinition app = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml")).services().get("app");
        Map<?, ?> build = (Map<?, ?>) app.configuration().get("build");

        assertEquals(Map.of("APP_MODE", "release", "LOG_LEVEL", "info", "DEBUG", "0"), build.get("args"));
        assertEquals(Map.of("net.ipv4.ip_forward", "1", "kernel.shm_rmid_forced", "0", "kernel.threads-max", "4096"),
                app.configuration().get("sysctls"));
        assertEquals(Map.of("com.example.mode", "release", "com.example.owner", "team-a"),
                app.configuration().get("annotations"));
        assertEquals(List.of("service.local:127.0.0.1", "service.local:127.0.0.2"),
                app.configuration().get("extra_hosts"));
    }

    @Test
    void mergesServiceAndBuildExtraHostsAcrossMappingAndListForms() throws IOException {
        Path directory = temporaryDirectory.resolve("overlay-extra-hosts-mixed-forms");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    extra_hosts:
                      cache.local: 192.0.2.1
                      db.local: 192.0.2.2
                    build:
                      context: .
                      extra_hosts: ["cache.local=192.0.2.1", "db.local=192.0.2.2"]
                """);
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    extra_hosts: ["cache.local=192.0.2.9", "api.local=192.0.2.3", "db.local=192.0.2.2"]
                    build:
                      extra_hosts:
                        cache.local: 192.0.2.9
                        api.local: 192.0.2.3
                        db.local: 192.0.2.2
                """);

        ServiceDefinition app = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml")).services().get("app");
        Map<?, ?> build = (Map<?, ?>) app.configuration().get("build");

        assertEquals(List.of("cache.local=192.0.2.1", "db.local=192.0.2.2", "cache.local=192.0.2.9",
                "api.local=192.0.2.3"), app.configuration().get("extra_hosts"));
        assertEquals(List.of("cache.local=192.0.2.1", "db.local=192.0.2.2", "cache.local=192.0.2.9",
                "api.local=192.0.2.3"), build.get("extra_hosts"));
    }

    @Test
    void mergesMappingAndListFormsOfComposeKeyedFields() throws IOException {
        Path directory = temporaryDirectory.resolve("overlay-keyed-mixed-forms");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    build:
                      context: .
                      args: [APP_MODE=base, LOG_LEVEL=info]
                      labels: {com.example.build: base, com.example.owner: team}
                      additional_contexts: [shared=./shared, base=./base]
                    environment: [APP_MODE=base, KEEP=1]
                    labels: {com.example.mode: base, com.example.owner: team}
                    annotations: [com.example.mode=base, com.example.owner=team]
                    sysctls: {net.ipv4.ip_forward: "0", kernel.shm_rmid_forced: "0"}
                  worker:
                    image: example/worker
                    build:
                      context: .
                      additional_contexts: {shared: ./shared, base: ./base}
                """);
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    build:
                      args: {APP_MODE: release, DEBUG: "0"}
                      labels: [com.example.build=release, com.example.version=1]
                      additional_contexts:
                        shared: ./replacement
                        docs: https://example.invalid/docs.git
                    environment: {APP_MODE: release, DEBUG: "0"}
                    labels: [com.example.mode=release, com.example.version=1]
                    annotations: {com.example.mode: release, com.example.version: "1"}
                    sysctls: [net.ipv4.ip_forward=1, kernel.threads-max=4096]
                  worker:
                    build:
                      additional_contexts: [shared=./replacement, docs=https://example.invalid/docs.git]
                """);

        ComposeProject project = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml"));
        ServiceDefinition app = project.services().get("app");
        Map<?, ?> build = (Map<?, ?>) app.configuration().get("build");

        assertEquals(Map.of("APP_MODE", "release", "LOG_LEVEL", "info", "DEBUG", "0"), build.get("args"));
        assertEquals(Map.of("com.example.build", "release", "com.example.owner", "team",
                "com.example.version", "1"), build.get("labels"));
        assertEquals(Map.of("shared", "./replacement", "base", "./base",
                "docs", "https://example.invalid/docs.git"), build.get("additional_contexts"));
        assertEquals(Map.of("APP_MODE", "release", "KEEP", "1", "DEBUG", "0"),
                app.configuration().get("environment"));
        assertEquals(Map.of("com.example.mode", "release", "com.example.owner", "team",
                "com.example.version", "1"), app.configuration().get("labels"));
        assertEquals(Map.of("com.example.mode", "release", "com.example.owner", "team",
                "com.example.version", "1"), app.configuration().get("annotations"));
        assertEquals(Map.of("net.ipv4.ip_forward", "1", "kernel.shm_rmid_forced", "0",
                "kernel.threads-max", "4096"), app.configuration().get("sysctls"));
        Map<?, ?> workerBuild = (Map<?, ?>) project.services().get("worker").configuration().get("build");
        assertEquals(Map.of("shared", "./replacement", "base", "./base",
                "docs", "https://example.invalid/docs.git"), workerBuild.get("additional_contexts"));
    }

    @Test
    void removesDuplicateEnvFileEntriesWhenMergingComposeFiles() throws IOException {
        Path directory = temporaryDirectory.resolve("overlay-env-file");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("app.env"), "FROM_FILE=loaded\n");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    env_file: [app.env, app.env]
                """);
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    env_file: [app.env]
                """);

        ServiceDefinition app = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml")).services().get("app");

        assertEquals(List.of("app.env"), app.configuration().get("env_file"));
        assertEquals("loaded", ((Map<?, ?>) app.configuration().get("environment")).get("FROM_FILE"));
    }

    @Test
    void resetMergeTagClearsInheritedSequence() throws IOException {
        Path directory = temporaryDirectory.resolve("reset-merge-tag");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    ports: ["8080:80"]
                """);
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    ports: !reset []
                """);

        ServiceDefinition app = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml")).services().get("app");

        assertEquals(List.of(), app.configuration().get("ports"));
    }

    @Test
    void overrideMergeTagReplacesInheritedSequenceWithoutMerging() throws IOException {
        Path directory = temporaryDirectory.resolve("override-merge-tag");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    ports: ["8080:80"]
                """);
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    ports: !override ["8443:443"]
                """);

        ServiceDefinition app = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml")).services().get("app");

        assertEquals(List.of("8443:443"), app.configuration().get("ports"));
    }

    @Test
    void usesFirstComposeFilesDotEnvForEveryMergedFile() throws IOException {
        Path directory = temporaryDirectory.resolve("project-dotenv-merge");
        Path overlayDirectory = Files.createDirectories(directory.resolve("overlay"));
        String variable = "JCOMPOSE_PROJECT_DOTENV_CONFORMANCE";
        Files.writeString(directory.resolve(".env"), variable + "=project\n");
        Files.writeString(overlayDirectory.resolve(".env"), variable + "=overlay\n");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    environment:
                      FROM_BASE: ${JCOMPOSE_PROJECT_DOTENV_CONFORMANCE}
                """);
        Files.writeString(overlayDirectory.resolve("compose.override.yaml"), """
                services:
                  app:
                    environment:
                      FROM_OVERLAY: ${JCOMPOSE_PROJECT_DOTENV_CONFORMANCE}
                """);

        ServiceDefinition app = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "overlay/compose.override.yaml")).services().get("app");
        Map<?, ?> environment = (Map<?, ?>) app.configuration().get("environment");

        assertEquals("project", environment.get("FROM_BASE"));
        assertEquals("project", environment.get("FROM_OVERLAY"));
    }

    @Test
    void replacesWindowsBindMountByContainerTarget() throws IOException {
        ComposeProject project = loadMerged("C:\\base:/data:ro", "D:\\override:/data:rw");

        assertEquals(List.of("D:\\override:/data:rw"), project.services().get("app").configuration().get("volumes"));
    }

    @Test
    void keepsDistinctWindowsContainerTargets() throws IOException {
        ComposeProject project = loadMerged("data:C:\\app", "logs:C:\\logs");

        assertEquals(List.of("data:C:\\app", "logs:C:\\logs"), project.services().get("app").configuration().get("volumes"));
    }

    @Test
    void keepsDistinctTargetOnlyWindowsVolumes() throws IOException {
        ComposeProject project = loadMerged("C:\\data", "D:\\data");

        assertEquals(List.of("C:\\data", "D:\\data"), project.services().get("app").configuration().get("volumes"));
    }

    @Test
    void replacesSingleLetterNamedVolumeByContainerTarget() throws IOException {
        ComposeProject project = loadMerged("a:/data", "b:/data");

        assertEquals(List.of("b:/data"), project.services().get("app").configuration().get("volumes"));
    }

    private ComposeProject loadFixture(String scenario) throws IOException {
        Path directory = copyFixture(scenario);
        if (Files.isRegularFile(directory.resolve(".env.example")))
            Files.copy(directory.resolve(".env.example"), directory.resolve(".env"));
        return new ComposeFileLoader().load(directory, "compose.yaml");
    }

    private Path copyFixture(String scenario) throws IOException {
        Path source = FIXTURES.resolve(scenario);
        Path destination = temporaryDirectory.resolve(scenario);
        Files.createDirectories(destination);
        try (var paths = Files.walk(source)) {
            for (Path file : paths.filter(Files::isRegularFile).toList())
                Files.copy(file, destination.resolve(source.relativize(file)));
        }
        return destination;
    }

    private ComposeProject loadMerged(String baseVolume, String overrideVolume) throws IOException {
        Path directory = temporaryDirectory.resolve("volume-merge");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                volumes:
                  a: {}
                  b: {}
                  data: {}
                  logs: {}
                services:
                  app:
                    image: example/app
                    volumes:
                      - '%s'
                """.formatted(baseVolume));
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    volumes:
                      - '%s'
                """.formatted(overrideVolume));
        return new ComposeFileLoader().loadMerged(directory, List.of("compose.yaml", "compose.override.yaml"));
    }
}
