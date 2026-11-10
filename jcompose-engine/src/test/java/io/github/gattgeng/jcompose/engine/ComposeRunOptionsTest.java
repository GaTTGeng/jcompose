package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeRunOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
