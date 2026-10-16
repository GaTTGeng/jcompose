package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.exception.DockerException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeStartMappingTest {
    @Test
    void ignoresOnlyDockerAlreadyStartedResponse() {
        assertTrue(ComposeService.isAlreadyStartedResponse(new DockerException("already started", 304)));
        assertFalse(ComposeService.isAlreadyStartedResponse(new DockerException("conflict", 409)));
        assertFalse(ComposeService.isAlreadyStartedResponse(new DockerException("failed", 500)));
    }
}
