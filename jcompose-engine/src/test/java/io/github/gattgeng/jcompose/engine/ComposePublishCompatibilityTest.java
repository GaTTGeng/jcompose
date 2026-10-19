package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.api.ComposePublishOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposePublishCompatibilityTest {
    @Test
    void rejectsOptionsThatWouldOtherwiseBeSilentlyIgnored() {
        ComposeProjectContext context = new ComposeProjectContext("publish-test", Path.of("."));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> new ComposeService().publishAsync(context, "example/application",
                        ComposePublishOptions.builder().withEnvironment(true).build()).join());

        assertEquals(UnsupportedOperationException.class, failure.getCause().getClass());
        assertEquals("Image digest resolution, environment layers, and custom OCI versions are not supported yet.",
                failure.getCause().getMessage());
    }

    @Test
    void requiresTheComposeFileBeforeConnectingToARegistry() {
        ComposeProjectContext context = new ComposeProjectContext("publish-test", Path.of("."));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> new ComposeService().publishAsync(context, "example/application:latest", null).join());

        assertEquals(IllegalArgumentException.class, failure.getCause().getClass());
        assertEquals("Compose file does not exist: " + context.workingDirectory().resolve("compose.yaml"),
                failure.getCause().getMessage());
    }
}
