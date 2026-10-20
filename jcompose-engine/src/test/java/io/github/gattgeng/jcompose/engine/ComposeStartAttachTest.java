package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeStartAttachTest {
    @Test
    void defaultsToBothStreamsAndHonorsStreamFilter() {
        assertEquals(new ComposeService.LogStreams(true, true), ComposeService.startAttachStreams(null));
        assertEquals(new ComposeService.LogStreams(true, false), ComposeService.startAttachStreams(List.of("stdout")));
        assertEquals(new ComposeService.LogStreams(false, true), ComposeService.startAttachStreams(List.of("stderr")));
    }

    @Test
    void rejectsUnsupportedAttachStreams() {
        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.startAttachStreams(List.of("database")));
    }
}
