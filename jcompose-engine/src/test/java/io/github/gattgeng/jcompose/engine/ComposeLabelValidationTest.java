package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeLabelValidationTest {
    @Test
    void rejectsReservedComposeLabelNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.validateComposeLabels(Map.of("com.docker.compose.project", "forged")));
        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.validateComposeLabels(Map.of("com.docker.compose", "forged")));
    }

    @Test
    void acceptsLabelsOutsideReservedComposeNamespace() {
        assertDoesNotThrow(() -> ComposeService.validateComposeLabels(Map.of("com.example.component", "api")));
    }

    @Test
    void rejectsScalingServicesWithFixedContainerNames() {
        ServiceDefinition named = new ServiceDefinition("app", Map.of("container_name", "app-container"));
        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.validateContainerNameScaling(named, 2));
        assertDoesNotThrow(() -> ComposeService.validateContainerNameScaling(named, 1));
        assertDoesNotThrow(() -> ComposeService.validateContainerNameScaling(
                new ServiceDefinition("app", Map.of()), 2));
    }

    @Test
    void rejectsNegativeReplicaCountsDuringValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.validateContainerNameScaling(new ServiceDefinition("app", Map.of()), -1));
    }

    @Test
    void rejectsSelectedServicesWithoutImageOrBuild() {
        ServiceDefinition profileService = new ServiceDefinition("helper", Map.of("profiles", java.util.List.of("tools")));

        assertThrows(IllegalArgumentException.class, () -> ComposeService.validateRunnableService(profileService));
        assertDoesNotThrow(() -> ComposeService.validateRunnableService(
                new ServiceDefinition("app", Map.of("image", "alpine"))));
        assertDoesNotThrow(() -> ComposeService.validateRunnableService(
                new ServiceDefinition("builder", Map.of("build", Map.of("context", ".")))));
    }
}
