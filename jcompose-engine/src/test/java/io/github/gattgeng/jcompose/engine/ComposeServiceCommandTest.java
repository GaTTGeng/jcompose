package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeServiceCommandTest {
    @Test
    void splitsScalarComposeCommandsWithoutEvaluatingShellSyntax() {
        assertEquals(List.of("bundle", "exec", "thin", "-p", "3000"),
                ComposeService.composeCommand("bundle exec thin -p 3000"));
        assertEquals(List.of("echo", "two words", "$HOME"),
                ComposeService.composeCommand("echo 'two words' $HOME"));
        assertEquals(List.of("echo", "a b"), ComposeService.composeCommand("echo a\\ b"));
        assertEquals(List.of("app", "reload"), ComposeService.composeCommand("app reload"));
    }

    @Test
    void preservesEmptyArgumentsAndExplicitEmptyCommands() {
        assertEquals(List.of("echo", ""), ComposeService.composeCommand("echo \"\""));
        assertEquals(List.of(), ComposeService.composeCommand(""));
    }

    @Test
    void representsEmptyComposeEntrypointsWithTheDockerEngineResetValue() {
        assertEquals(List.of(""), ComposeService.composeEntrypoint(""));
        assertEquals(List.of(""), ComposeService.composeEntrypoint(List.of()));
        assertEquals(List.of("sh", "-c"), ComposeService.composeEntrypoint("sh -c"));
    }

    @Test
    void rejectsUnmatchedQuotesAndEscapes() {
        assertThrows(IllegalArgumentException.class, () -> ComposeService.composeCommand("echo 'unfinished"));
        assertThrows(IllegalArgumentException.class, () -> ComposeService.composeCommand("echo \\"));
    }
}
