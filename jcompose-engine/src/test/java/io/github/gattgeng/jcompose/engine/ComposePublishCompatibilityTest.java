package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposePublishCompatibilityTest {
    @Test
    void rejectsImageRetaggingAsComposeApplicationPublishing() {
        ComposeProjectContext context = new ComposeProjectContext("publish-test", Path.of("."));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> new ComposeService().publishAsync(context, "example/application", null).join());

        assertEquals(UnsupportedOperationException.class, failure.getCause().getClass());
        assertEquals("Compose application publishing as an OCI artifact is not implemented yet.",
                failure.getCause().getMessage());
    }
}
