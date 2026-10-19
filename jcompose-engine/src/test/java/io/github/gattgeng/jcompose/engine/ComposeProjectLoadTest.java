package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComposeProjectLoadTest {
    @TempDir
    Path directory;

    @Test
    void synchronouslyLoadsConfigurationWithoutDocker() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                """);

        var project = new ComposeService().loadProject(new ComposeProjectContext("test", directory));

        assertEquals("test", project.name());
        assertEquals(java.util.List.of("app"), project.services());
        assertNull(project.renderedYaml());
    }

    @Test
    void asynchronousLoadRemainsAvailable() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                """);

        var project = new ComposeService().loadProjectAsync(new ComposeProjectContext("test", directory)).join();

        assertEquals(java.util.List.of("app"), project.services());
        assertNull(project.renderedYaml());
    }
}
