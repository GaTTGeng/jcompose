package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeExecOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeExecOptionsTest {
    @Test
    void supportsDetachKeysAndPreservesThePreviousConstructor() {
        var configured = ComposeExecOptions.builder()
                .command(List.of("sh"))
                .detachKeys("ctrl-a,ctrl-b")
                .build();
        assertEquals("ctrl-a,ctrl-b", configured.detachKeys());
        assertTrue(configured.tty());
        assertTrue(configured.interactive());

        var legacy = new ComposeExecOptions(List.of("sh"), false, null, null,
                false, null, null, false, false);
        assertNull(legacy.detachKeys());
        assertFalse(legacy.tty());
        assertFalse(legacy.interactive());
    }
}
