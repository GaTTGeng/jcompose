package io.github.gattgeng.jcompose.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposeFileLoaderTest {
    @TempDir
    Path directory;

    @Test
    void inlineEnvironmentOverridesEnvFiles() throws Exception {
        Files.writeString(directory.resolve("app.env"), "MODE=from-file\nFILE_ONLY=yes\n");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    env_file: app.env
                    environment:
                      MODE: inline
                """);

        var project = new ComposeFileLoader().load(directory, null);
        assertEquals(Map.of("MODE", "inline", "FILE_ONLY", "yes"),
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
