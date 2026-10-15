package io.github.gattgeng.jcompose.engine;

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
}
