package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.exception.DockerException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeTopMappingTest {
    @Test
    void treatsOnlyNotFoundAndConflictAsContainerListingRaces() {
        assertTrue(ComposeService.isTopListingRace(new DockerException("removed", 404)));
        assertTrue(ComposeService.isTopListingRace(new DockerException("stopped", 409)));
        assertFalse(ComposeService.isTopListingRace(new DockerException("failed", 500)));
    }
}
