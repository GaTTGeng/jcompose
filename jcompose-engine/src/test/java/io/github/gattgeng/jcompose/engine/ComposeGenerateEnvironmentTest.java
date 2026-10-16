package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposeGenerateEnvironmentTest {
    @TempDir
    Path directory;

    @Test
    void returnsEffectiveInterpolationEnvironmentWithoutDocker() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: "${JC_GENERATE_ENV_UNUSED:-alpine}"
                """);
        Files.writeString(directory.resolve(".env"), "JC_GENERATE_ENV_UNUSED=from-dotenv\n");

        var config = new ComposeService().generateAsync(new ComposeProjectContext("test", directory), null).join();

        assertEquals("from-dotenv", config.environment().get("JC_GENERATE_ENV_UNUSED"));
    }
}
