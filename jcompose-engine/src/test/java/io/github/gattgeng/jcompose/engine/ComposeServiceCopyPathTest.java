package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeServiceCopyPathTest {
    @Test
    void recognizesServiceContainerPathsOnEveryHostPlatform() {
        ComposeService.CopyPath path = ComposeService.CopyPath.parse("app:/var/lib/data");

        assertTrue(path.container());
        assertEquals("app", path.service());
        assertEquals("/var/lib/data", path.path());
    }

    @Test
    void keepsWindowsDrivePathsAsHostPaths() {
        assertHostPath("C:\\temp\\fixture.txt");
        assertHostPath("C:/temp/fixture.txt");
    }

    @Test
    void treatsOtherPathsAsHostPaths() {
        assertHostPath("./fixture.txt");
        assertHostPath("fixture:name.txt");
    }

    private static void assertHostPath(String value) {
        ComposeService.CopyPath path = ComposeService.CopyPath.parse(value);

        assertFalse(path.container());
        assertEquals(value, path.path());
    }
}
