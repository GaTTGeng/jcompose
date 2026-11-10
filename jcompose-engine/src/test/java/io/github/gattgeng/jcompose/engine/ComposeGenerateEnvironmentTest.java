package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeGenerateEnvironmentTest {
    @TempDir
    Path directory;

    @Test
    void returnsEffectiveInterpolationEnvironmentWithoutDocker() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: "${JC_GENERATE_ENV_UNUSED:-alpine}"
                """);
        Files.writeString(directory.resolve(".env"), "JC_GENERATE_ENV_UNUSED=from-dotenv\n");

        var config = new ComposeService().generateAsync(new ComposeProjectContext("test", directory), null).join();

        assertEquals("from-dotenv", config.environment().get("JC_GENERATE_ENV_UNUSED"));
    }

    @Test
    void exposesContextProjectNameToComposeInterpolation() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                    command: echo ${COMPOSE_PROJECT_NAME}
                """);

        var config = new ComposeService().generateAsync(
                new ComposeProjectContext("context-project", directory), null).join();

        assertEquals("context-project", config.environment().get("COMPOSE_PROJECT_NAME"));
        org.junit.jupiter.api.Assertions.assertTrue(config.renderedYaml().contains("echo context-project"));
    }

    @Test
    void generatesConfigurationFromOrderedComposeFiles() throws Exception {
        Files.writeString(directory.resolve("base.yaml"), """
                services:
                  app:
                    image: alpine
                    command: base
                """);
        Files.writeString(directory.resolve("local.yaml"), """
                services:
                  app:
                    command: local
                  worker:
                    image: busybox
                """);
        var context = new ComposeProjectContext("test", directory, "base.yaml", null,
                null, null, false, false, java.util.List.of("base.yaml", "local.yaml"));

        var config = new ComposeService().generateAsync(context, null).join();

        assertEquals(java.util.List.of("base.yaml", "local.yaml"), config.configFiles());
        assertEquals(java.util.Set.of("app", "worker"), java.util.Set.copyOf(config.services()));
        org.junit.jupiter.api.Assertions.assertTrue(config.renderedYaml().contains("command: local"));
    }

    @Test
    void rendersRetainedTopLevelResourceDefinitions() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                volumes:
                  data:
                    driver: local
                    driver_opts:
                      type: nfs
                networks:
                  backend:
                    driver: bridge
                    ipam:
                      config:
                        - subnet: 10.30.0.0/16
                secrets:
                  database-password:
                    file: ./database-password.txt
                configs:
                  app-settings:
                    environment: APP_SETTINGS
                """);

        var config = new ComposeService().generateAsync(new ComposeProjectContext("test", directory), null).join();
        String yaml = config.renderedYaml();

        assertTrue(yaml.contains("driver: local"));
        assertTrue(yaml.contains("type: nfs"));
        assertTrue(yaml.contains("driver: bridge"));
        assertTrue(yaml.contains("subnet: 10.30.0.0/16"));
        assertTrue(yaml.contains("file: ./database-password.txt"));
        assertTrue(yaml.contains("environment: APP_SETTINGS"));
    }
}
