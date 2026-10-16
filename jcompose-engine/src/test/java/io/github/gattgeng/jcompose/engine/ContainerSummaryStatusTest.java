package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContainerSummaryStatusTest {
    @Test
    void readsHealthFromDockerStatus() {
        assertEquals("healthy", ComposeService.containerHealth("Up 2 minutes (healthy)"));
        assertEquals("starting", ComposeService.containerHealth("Up 2 seconds (health: starting)"));
        assertNull(ComposeService.containerHealth("Up 2 minutes"));
    }

    @Test
    void readsExactExitCodeFromDockerStatus() {
        assertEquals(137, ComposeService.containerExitCode("Exited (137) 2 seconds ago"));
        assertEquals(0, ComposeService.containerExitCode("Up 2 minutes"));
        assertEquals(0, ComposeService.containerExitCode(null));
    }
}
