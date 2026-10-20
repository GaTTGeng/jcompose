package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.api.ComposeValidationException;
import io.github.gattgeng.jcompose.api.ComposeValidationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void reportsUnsupportedFieldsWithoutExposingTheirValues() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                custom_project_option: private-value
                services:
                  app:
                    image: alpine
                    logging:
                      driver: private-driver
                    custom_service_option: private-value
                """);

        var project = new ComposeService().loadProject(new ComposeProjectContext("test", directory));

        assertTrue(project.diagnostics().stream().anyMatch(d -> d.propertyPath().equals("custom_project_option")
                && d.code().equals("unsupported-field")));
        assertTrue(project.diagnostics().stream().anyMatch(d -> d.propertyPath().equals("services.app.logging")
                && d.serviceName().equals("app")));
        assertTrue(project.diagnostics().stream().noneMatch(d -> d.message().contains("private-value")
                || d.message().contains("private-driver")));
    }

    @Test
    void strictValidationRejectsReportedFieldsBeforeDockerAccess() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: alpine
                    custom_service_option: enabled
                """);
        var context = new ComposeProjectContext("test", directory, "compose.yaml", null, null, null,
                false, false, null, ComposeValidationMode.STRICT);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeService().loadProject(context));

        assertEquals("services.app.custom_service_option", exception.propertyPath());
        assertEquals("app", exception.serviceName());
    }
}
