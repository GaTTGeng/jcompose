package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComposeResourceMappingTest {
    @Test
    void treatsZeroMemorySwapAsUnset() {
        assertNull(ComposeService.memorySwapLimit("0"));
        assertNull(ComposeService.memorySwapLimit("0b"));
    }

    @Test
    void preservesUnlimitedAndPositiveMemorySwapLimits() {
        assertEquals(-1L, ComposeService.memorySwapLimit("-1"));
        assertEquals(128L * 1024 * 1024, ComposeService.memorySwapLimit("128m"));
    }
}
