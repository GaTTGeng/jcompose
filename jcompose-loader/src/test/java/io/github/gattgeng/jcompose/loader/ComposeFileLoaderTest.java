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
