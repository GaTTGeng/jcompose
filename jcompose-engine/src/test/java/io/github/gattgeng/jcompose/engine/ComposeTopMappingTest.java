package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.exception.DockerException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeTopMappingTest {
    @Test
    void treatsOnlyNotFoundAndConflictAsContainerListingRaces() {
        assertTrue(ComposeService.isTopListingRace(new DockerException("removed", 404)));
        assertTrue(ComposeService.isTopListingRace(new DockerException("stopped", 409)));
        assertFalse(ComposeService.isTopListingRace(new DockerException("failed", 500)));
    }

    @Test
    void mapsMissingProcessTitlesRowsAndCellsToEmptyValues() {
        assertEquals(java.util.List.of(), ComposeService.mapTopTitles(null));
        assertEquals(java.util.List.of("PID", ""), ComposeService.mapTopTitles(new String[]{"PID", null}));
        assertEquals(java.util.List.of(), ComposeService.mapTopProcesses(null));
        assertEquals(java.util.List.of(java.util.List.of("1", ""), java.util.List.of()),
                ComposeService.mapTopProcesses(new String[][]{{"1", null}, null}));
    }
}
