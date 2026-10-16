package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StackStatusTest {
    @Test
    void derivesStatusFromSupportedContainerStates() {
        assertEquals("running", ComposeService.stackStatus(List.of("running", "running")));
        assertEquals("exited", ComposeService.stackStatus(List.of("exited", "dead")));
        assertEquals("partial", ComposeService.stackStatus(List.of("running", "exited")));
    }

    @Test
    void reportsUnknownForPausedRestartingAndMissingStates() {
        assertEquals("unknown", ComposeService.stackStatus(List.of("paused")));
        assertEquals("unknown", ComposeService.stackStatus(List.of("running", "restarting")));
        assertEquals("unknown", ComposeService.stackStatus(List.of()));
    }
}
