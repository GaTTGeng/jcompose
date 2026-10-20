package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetachKeyInputStreamTest {
    @Test
    void parsesDockerControlKeySequences() {
        assertArrayEquals(new byte[]{16, 17}, DetachKeyInputStream.parse("ctrl-p,ctrl-q"));
        assertArrayEquals(new byte[]{'x'}, DetachKeyInputStream.parse("x"));
    }

    @Test
    void removesDetachSequenceAndSignalsDetach() throws IOException {
        AtomicBoolean detached = new AtomicBoolean();
        byte[] input = new byte[]{'h', 'i', 16, 17, 'x'};
        try (var stream = new DetachKeyInputStream(new ByteArrayInputStream(input), "ctrl-p,ctrl-q",
                () -> detached.set(true))) {
            assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), stream.readAllBytes());
        }
        assertTrue(detached.get());
    }

    @Test
    void preservesInputWhenDetachSequenceDoesNotMatch() throws IOException {
        AtomicBoolean detached = new AtomicBoolean();
        byte[] input = new byte[]{16, 'x', 17};
        try (var stream = new DetachKeyInputStream(new ByteArrayInputStream(input), "ctrl-p,ctrl-q",
                () -> detached.set(true))) {
            assertArrayEquals(input, stream.readAllBytes());
        }
        assertFalse(detached.get());
    }

    @Test
    void rejectsInvalidDockerDetachKeySyntax() {
        assertThrows(IllegalArgumentException.class, () -> DetachKeyInputStream.parse("ctrl-nope"));
    }
}
