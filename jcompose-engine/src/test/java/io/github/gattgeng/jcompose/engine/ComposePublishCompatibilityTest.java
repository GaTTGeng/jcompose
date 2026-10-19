package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.api.ComposePublishOptions;
import io.github.gattgeng.jcompose.api.ComposePushOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposePublishCompatibilityTest {
    @Test
    void convertsOnlyDockerAndRegistryPushFailuresIntoPerServiceResults() {
        RuntimeException dockerFailure = new com.github.dockerjava.api.exception.DockerException("Docker error", 500);
        RuntimeException registryFailure = new IllegalStateException("registry rejected push");
        RuntimeException unexpectedFailure = new IllegalArgumentException("invalid internal state");
        RuntimeException cancellation = new java.util.concurrent.CancellationException();

        assertTrue(ComposeService.isImageTagFailure(dockerFailure));
        assertFalse(ComposeService.isImageTagFailure(registryFailure));
        assertFalse(ComposeService.isImageTagFailure(cancellation));
        assertTrue(ComposeService.isImageTransferFailure(dockerFailure));
        assertTrue(ComposeService.isImageTransferFailure(registryFailure));
        assertFalse(ComposeService.isImageTransferFailure(unexpectedFailure));
        assertFalse(ComposeService.isImageTransferFailure(cancellation));

        ComposePushOptions ignoreFailures = ComposePushOptions.builder().ignoreFailures(true).build();
        assertTrue(ComposeService.shouldIgnorePushFailure(ignoreFailures, dockerFailure));
        assertTrue(ComposeService.shouldIgnorePushFailure(ignoreFailures, registryFailure));
        assertFalse(ComposeService.shouldIgnorePushFailure(ignoreFailures, unexpectedFailure));
        assertFalse(ComposeService.shouldIgnorePushFailure(ignoreFailures, cancellation));
        assertFalse(ComposeService.shouldIgnorePushFailure(null, dockerFailure));
        ComposePushOptions propagateFailures = ComposePushOptions.builder().ignoreFailures(false).build();
        assertFalse(ComposeService.shouldIgnorePushFailure(propagateFailures, dockerFailure));
    }

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
        assertEquals("Compose file not found from " + context.workingDirectory() + ": compose.yaml",
                failure.getCause().getMessage());
    }

    @Test
    void imagePublishRequiresTheComposeFileBeforeConnectingToDocker() {
        ComposeProjectContext context = new ComposeProjectContext("publish-test", Path.of("."));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> new ComposeService().publishImagesAsync(context, "example/application", null).join());

        assertEquals(IllegalArgumentException.class, failure.getCause().getClass());
        assertEquals("Compose file not found from " + context.workingDirectory() + ": compose.yaml",
                failure.getCause().getMessage());
    }

    @Test
    void rendersMergedComposeFilesAsOnePublishableConfiguration(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  web:
                    image: nginx:latest
                """);
        Files.writeString(directory.resolve("compose.prod.yaml"), """
                services:
                  web:
                    environment:
                      MODE: production
                  worker:
                    image: busybox:latest
                """);
        ComposeProjectContext context = new ComposeProjectContext("publish-test", directory,
                "compose.yaml", List.of(), null, null, false, false,
                List.of("compose.yaml", "compose.prod.yaml"));

        String yaml = new ComposeService().publishComposeYaml(context);

        assertTrue(yaml.contains("image: nginx:latest"));
        assertTrue(yaml.contains("MODE: production"));
        assertTrue(yaml.contains("worker:"));
        assertTrue(yaml.contains("image: busybox:latest"));
    }
}
