package io.github.gattgeng.jcompose.loader;

import io.github.gattgeng.jcompose.api.ComposeValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeCommandParserTest {
    @TempDir
    Path directory;

    @Test
    void splitsQuotedScalarCommandsWithoutShellEvaluation() {
        assertEquals(List.of("echo", "two words", "$HOME"),
                ComposeCommandParser.parse("echo 'two words' $HOME"));
        assertEquals(List.of("echo", "a b"), ComposeCommandParser.parse("echo a\\ b"));
        assertEquals(List.of("echo", ""), ComposeCommandParser.parse("echo \"\""));
    }

    @Test
    void loaderRejectsMalformedScalarCommandsWithComposePropertyContext() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox
                    command: "echo 'unfinished"
                """);

        ComposeValidationException exception = assertThrows(ComposeValidationException.class,
                () -> new ComposeFileLoader().load(directory, "compose.yaml"));

        assertEquals("services.app.command", exception.propertyPath());
        assertEquals("app", exception.serviceName());
        assertEquals("invalid command line string", exception.getCause().getMessage());
    }
}
