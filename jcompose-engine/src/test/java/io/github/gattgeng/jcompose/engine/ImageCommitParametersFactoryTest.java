package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeCommitOptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageCommitParametersFactoryTest {
    @Test
    void mapsDockerfileInstructions() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("CMD", "[\"app\"]");
        changes.put(" WORKDIR ", "/srv");
        changes.put("VOLUME", " ");
        assertEquals(java.util.List.of("CMD [\"app\"]", "WORKDIR /srv", "VOLUME"),
                ImageCommitParametersFactory.toDockerfileChanges(changes));
    }

    @Test
    void rejectsBlankInstructionNames() {
        assertThrows(IllegalArgumentException.class,
                () -> ImageCommitParametersFactory.toDockerfileChanges(Map.of(" ", "value")));
    }

    @Test
    void encodesEachInstructionAsARepeatedQueryParameter() {
        assertEquals("/v1.49/commit?container=id&changes=CMD%20%5B%22app%22%5D&changes=WORKDIR%20%2Fsrv",
                DockerClientFactory.appendCommitChanges("/v1.49/commit?container=id",
                        java.util.List.of("CMD [\"app\"]", "WORKDIR /srv")));
    }

    @Test
    void leavesOtherEndpointsAndEmptyInstructionsUntouched() {
        assertEquals("/containers/json", DockerClientFactory.appendCommitChanges("/containers/json", java.util.List.of("CMD x")));
        assertEquals("/commit?container=id", DockerClientFactory.appendCommitChanges("/commit?container=id", java.util.List.of()));
    }

    @Test
    void preservesCommitInstructionOrderInImmutableOptions() {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("ENTRYPOINT", "[\"app\"]");
        changes.put("CMD", "[\"serve\"]");
        ComposeCommitOptions options = new ComposeCommitOptions("api", null, null, null, changes, true, null);
        changes.clear();
        assertEquals(java.util.List.of("ENTRYPOINT [\"app\"]", "CMD [\"serve\"]"),
                ImageCommitParametersFactory.toDockerfileChanges(options.changes()));
    }
}
