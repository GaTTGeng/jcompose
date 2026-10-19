package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeVolumeSpecTest {
    @Test
    void keepsWindowsDriveColonInsideBindSource() {
        ComposeService.VolumeSpec specification = ComposeService.parseVolumeSpec("C:\\data:/var/lib/app:ro");

        assertEquals("C:\\data", specification.source());
        assertEquals("/var/lib/app", specification.target());
        assertEquals("ro", specification.mode());
    }

    @Test
    void parsesNamedAndAnonymousVolumeSources() {
        ComposeService.VolumeSpec named = ComposeService.parseVolumeSpec("app-data:/var/lib/app:rw");
        ComposeService.VolumeSpec anonymous = ComposeService.parseVolumeSpec(":/var/lib/app");

        assertEquals("app-data", named.source());
        assertEquals("/var/lib/app", named.target());
        assertEquals("", anonymous.source());
        assertEquals("/var/lib/app", anonymous.target());
        assertEquals("rw", anonymous.mode());
    }

    @Test
    void recognizesReadonlyAsACommaSeparatedModeFlag() {
        assertTrue(ComposeService.volumeIsReadOnly("z,readonly"));
    }

    @Test
    void rejectsVolumeSpecificationsWithoutAContainerTarget() {
        assertNull(ComposeService.parseVolumeSpec("app-data:"));
    }
}
