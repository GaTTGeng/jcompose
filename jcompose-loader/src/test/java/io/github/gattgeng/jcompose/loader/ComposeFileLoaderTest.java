package io.github.gattgeng.jcompose.loader;

import io.github.gattgeng.jcompose.api.ComposeValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
