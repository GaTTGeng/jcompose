package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposePortOptions;
import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposePortProtocolTest {
    @Test
    void rejectsProtocolsNotSupportedByDockerComposePort() {
        CompletionException exception = assertThrows(CompletionException.class, () ->
                new ComposeService().portAsync(
                        new ComposeProjectContext("test", Path.of(".")),
                        "app", 8080, ComposePortOptions.builder().protocol("sctp").build()).join());

        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }
}
