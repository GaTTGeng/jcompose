package io.github.gattgeng.jcompose.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeScalarSequenceMergeTest {
    @TempDir Path directory;

    @Test
    void mergesScalarAndListFormsInEitherDirection() throws Exception {
        Files.writeString(directory.resolve("first.env"), "FIRST=one\nSHARED=base\n");
        Files.writeString(directory.resolve("second.env"), "SECOND=two\nSHARED=overlay\n");
        Files.writeString(directory.resolve("first.labels"), "first=one\n");
        Files.writeString(directory.resolve("second.labels"), "second=two\n");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox
                    dns: 1.1.1.1
                    dns_search: [base.example]
                    dns_opt: [ndots:2]
                    env_file: first.env
                    label_file: first.labels
                    tmpfs: /run
                """);
        Files.writeString(directory.resolve("overlay.yaml"), """
                services:
                  app:
                    dns: [1.1.1.1, 8.8.8.8]
                    dns_search: overlay.example
                    dns_opt: [timeout:1]
                    env_file: [first.env, second.env]
                    label_file: [second.labels]
                    tmpfs: [/run, /cache]
                """);

        var service = new ComposeFileLoader().loadMerged(directory, List.of("compose.yaml", "overlay.yaml"))
                .services().get("app");

        assertEquals(List.of("1.1.1.1", "8.8.8.8"), service.dns());
        assertEquals(List.of("base.example", "overlay.example"), service.dnsSearch());
        assertEquals(List.of("ndots:2", "timeout:1"), service.dnsOpt());
        assertEquals(List.of("first.env", "second.env"), service.envFile());
        assertEquals("one", service.environment().get("FIRST"));
        assertEquals("two", service.environment().get("SECOND"));
        assertEquals("overlay", service.environment().get("SHARED"));
        assertEquals("one", service.labels().get("first"));
        assertEquals("two", service.labels().get("second"));
        assertEquals(List.of("/run", "/cache"), service.tmpfs());
    }

    @Test
    void laterEnvFileDefinitionReplacesTheSamePathAcrossForms() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox
                    env_file: [missing.env]
                """);
        Files.writeString(directory.resolve("overlay.yaml"), """
                services:
                  app:
                    env_file:
                      - path: missing.env
                        required: false
                """);

        var loader = new ComposeFileLoader();
        var app = loader.loadMerged(directory, List.of("compose.yaml", "overlay.yaml")).services().get("app");
        assertEquals(List.of(Map.of("path", "missing.env", "required", false)), app.envFile());

        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox
                    env_file:
                      - path: missing.env
                        required: false
                """);
        Files.writeString(directory.resolve("overlay.yaml"), """
                services:
                  app:
                    env_file: [missing.env]
                """);
        assertThrows(io.github.gattgeng.jcompose.api.ComposeValidationException.class,
                () -> loader.loadMerged(directory, List.of("compose.yaml", "overlay.yaml")));
    }
}
