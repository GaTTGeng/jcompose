package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeVolumeSpecTest {
    @TempDir
    Path temporaryDirectory;

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

    @Test
    void mapsBindPropagationAndSelinuxOptions() {
        var bind = ComposeService.serviceBind("/host/data", "/data", true, "z", "rshared");

        assertEquals(com.github.dockerjava.api.model.AccessMode.ro, bind.getAccessMode());
        assertEquals(com.github.dockerjava.api.model.SELContext.shared, bind.getSecMode());
        assertEquals(com.github.dockerjava.api.model.PropagationMode.RSHARED, bind.getPropagationMode());
    }

    @Test
    void rejectsUnknownBindPropagationAndSelinuxOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.serviceBind("/host/data", "/data", false, "x", null));
        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.serviceBind("/host/data", "/data", false, null, "recursive"));
    }

    @Test
    void createsMissingBindSourceDirectoriesByDefault() {
        Path source = temporaryDirectory.resolve("nested/data");

        ComposeService.prepareBindSource(source, true);

        assertTrue(Files.isDirectory(source));
    }

    @Test
    void doesNotCreateMissingBindSourceWhenDisabled() {
        Path source = temporaryDirectory.resolve("must-exist");

        assertThrows(IllegalArgumentException.class, () -> ComposeService.prepareBindSource(source, false));

        assertTrue(Files.notExists(source));
    }

    @Test
    void mapsLongSyntaxTmpfsMountAndSize() {
        Map<String, String> mounts = ComposeService.longSyntaxTmpfsMounts(List.of(Map.of(
                "type", "tmpfs", "target", "/run/cache", "tmpfs", Map.of("size", "1m"))));

        assertEquals(Map.of("/run/cache", "size=1048576"), mounts);
    }

    @Test
    void rejectsTmpfsMountWithoutTarget() {
        assertThrows(IllegalArgumentException.class, () -> ComposeService.longSyntaxTmpfsMounts(List.of(
                Map.of("type", "tmpfs", "tmpfs", Map.of("size", 1024)))));
    }
}
