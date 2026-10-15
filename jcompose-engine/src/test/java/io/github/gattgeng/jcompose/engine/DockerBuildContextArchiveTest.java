package io.github.gattgeng.jcompose.engine;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DockerBuildContextArchiveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bracketClassTreatsExclamationMarkAsLiteral() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "[!a]\n");
        Files.writeString(context.resolve("a"), "excluded by the class\n");
        Files.writeString(context.resolve("b"), "not matched\n");
        Files.writeString(context.resolve("!"), "excluded by the class\n");

        Set<String> entries = new HashSet<>();
        try (DockerBuildContextArchive archive = DockerBuildContextArchive.create(context, dockerfile);
             TarArchiveInputStream tar = new TarArchiveInputStream(archive.stream())) {
            for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry())
                entries.add(entry.getName());
        }

        assertEquals(Set.of("Dockerfile", ".dockerignore", "b"), entries);
    }
}
