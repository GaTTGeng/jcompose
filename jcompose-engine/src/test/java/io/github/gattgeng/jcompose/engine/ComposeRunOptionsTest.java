package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeRunOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeRunOptionsTest {
    @Test
    void keepsDockerDefaultWhenDetachKeysAreNotConfigured() {
        assertNull(ComposeRunOptions.builder().build().detachKeys());
    }

    @Test
    void exposesCustomDetachKeys() {
        assertEquals("ctrl-a,ctrl-b", ComposeRunOptions.builder()
                .detachKeys("ctrl-a,ctrl-b").build().detachKeys());
    }

    @Test
    void buildsTheServiceImageOnlyWhenRequested() {
        assertFalse(ComposeRunOptions.builder().build().build());
        assertTrue(ComposeRunOptions.builder().build(true).build().build());
    }
}
