package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeStartOptions;
import io.github.gattgeng.jcompose.api.ILogConsumer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeStartAttachTest {
    private static final ILogConsumer CONSUMER = new ILogConsumer() {
        @Override public void onLog(String serviceName, String message, boolean standardError) { }
        @Override public void onLogComplete(String serviceName) { }
        @Override public void onStatus(String serviceName, String message) { }
    };

    @Test
    void defaultsToSelectedServicesAndHonorsAttachToFilter() {
        List<String> selected = List.of("database", "web");

        assertEquals(selected, ComposeService.startAttachServices(
                ComposeStartOptions.builder().attach(CONSUMER).build(), selected));
        assertEquals(List.of("web"), ComposeService.startAttachServices(
                ComposeStartOptions.builder().attach(CONSUMER).attachTo(List.of("web")).build(), selected));
    }

    @Test
    void rejectsAttachToServicesOutsideTheStartSelection() {
        ComposeStartOptions options = ComposeStartOptions.builder().attach(CONSUMER)
                .attachTo(List.of("database")).build();

        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.startAttachServices(options, List.of("web")));
    }
}
