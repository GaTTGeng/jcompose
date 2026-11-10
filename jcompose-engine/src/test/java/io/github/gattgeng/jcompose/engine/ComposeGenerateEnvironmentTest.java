package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.loader.ComposeFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void generatedConfigurationCanBeReloadedWithoutLosingRetainedValues() throws Exception {
        Files.writeString(directory.resolve("worker.env"), "FROM_FILE=present\nOVERRIDE=file\n");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  worker:
                    image: example/worker:latest
                    build:
                      context_directory: ./ctx
                    command: ["echo", "a: b # c"]
                    env_file: [worker.env]
                    environment:
                      - "TOKEN=$$CASH"
                      - "OVERRIDE=inline"
                    ports:
                      - "5353:53/udp"
                    restart: "on-failure:3"
                    labels:
                      "cost$code": charge
                    healthcheck:
                      test: ["CMD-SHELL", "echo ready"]
                      interval: 1500ms
                    deploy:
                      replicas: 2
                      restart_policy:
                        max_attempts: 4
                      resources:
                        limits:
                          nano_cpus: "250000000"
                networks:
                  private:
                    driver: bridge
                volumes:
                  state:
                """);

        var generated = new ComposeService().generateAsync(new ComposeProjectContext("sample", directory), null).join();
        assertTrue(generated.renderedYaml().contains("$$CASH"));
        assertTrue(generated.renderedYaml().contains("cost$code"), generated.renderedYaml());
        assertTrue(generated.renderedYaml().contains("driver: bridge"));
        assertFalse(generated.renderedYaml().contains("env_file"));
        Files.writeString(directory.resolve("generated.yaml"), generated.renderedYaml());

        var worker = new ComposeFileLoader().load(directory, "generated.yaml").services().get("worker");

        assertEquals(java.util.List.of("echo", "a: b # c"), worker.command());
        assertEquals("present", worker.environment().get("FROM_FILE"));
        assertEquals("inline", worker.environment().get("OVERRIDE"));
        assertEquals("$CASH", worker.environment().get("TOKEN"));
        assertEquals("5353", worker.ports().getFirst().published());
        assertEquals("53", worker.ports().getFirst().target());
        assertEquals("udp", worker.ports().getFirst().protocol());
        assertEquals("on-failure:3", worker.restart());
        assertEquals("charge", worker.labels().get("cost$code"));
        assertEquals(java.time.Duration.ofMillis(1500), worker.healthcheckConfig().interval());
        assertEquals(2, worker.deployConfig().replicas());
        assertEquals(4, worker.deployConfig().restartPolicy().maxRetries());
        assertEquals("250000000", worker.deployConfig().resources().limits().nanoCpus());
        assertEquals("./ctx", worker.buildConfig().contextDirectory());
    }
}
