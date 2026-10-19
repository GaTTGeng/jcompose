package io.github.gattgeng.jcompose.engine;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerBuildContextArchiveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void addsInlineDockerfileToBuildContextArchive() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("inline-context"));
        Files.writeString(context.resolve("source.txt"), "build input");
        Files.writeString(context.resolve(".dockerignore"), "secret.txt\n");
        Files.writeString(context.resolve("secret.txt"), "excluded");
        String dockerfile = "FROM scratch\nCOPY source.txt /source.txt\n";

        try (DockerBuildContextArchive archive = DockerBuildContextArchive.createInline(context, dockerfile);
             TarArchiveInputStream tar = new TarArchiveInputStream(archive.stream())) {
            boolean foundDockerfile = false;
            boolean foundSource = false;
            boolean foundSecret = false;
            for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                if (entry.getName().startsWith("__external_dockerfile__")) {
                    assertEquals(dockerfile, new String(tar.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    foundDockerfile = true;
                } else if (entry.getName().equals("source.txt")) {
                    foundSource = true;
                } else if (entry.getName().equals("secret.txt")) {
                    foundSecret = true;
                }
            }
            assertTrue(foundDockerfile);
            assertTrue(foundSource);
            assertTrue(!foundSecret);
        }
    }

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

    @Test
    void dockerfileSpecificIgnoreFileTakesPrecedenceOverContextIgnoreFile() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("specific-ignore-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile.dev"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "secret\n");
        Files.writeString(context.resolve("Dockerfile.dev.dockerignore"), "public\n");
        Files.writeString(context.resolve("secret"), "included\n");
        Files.writeString(context.resolve("public"), "excluded\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(entries.contains("secret"));
        assertTrue(!entries.contains("public"));
    }

    @Test
    void stripsUtf8BomBeforeReadingIgnorePatterns() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("bom-ignore-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "\uFEFFsecret\n");
        Files.writeString(context.resolve("secret"), "excluded\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(!entries.contains("secret"));
    }

    @Test
    void watchIgnoreMatcherAppliesDockerignoreAndGitDefaults() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("watch-ignore-context"));
        Files.writeString(context.resolve(".dockerignore"), "private/**\n");
        Path privateFile = Files.createDirectories(context.resolve("private")).resolve("secret.txt");
        Path gitFile = Files.createDirectories(context.resolve(".git")).resolve("config");
        Path publicFile = Files.writeString(context.resolve("Main.java"), "public");

        var ignored = DockerBuildContextArchive.watchIgnoreMatcher(context, context.resolve("Dockerfile"));

        assertTrue(ignored.test(privateFile));
        assertTrue(ignored.test(gitFile));
        assertTrue(!ignored.test(publicFile));
    }

    @Test
    void watchIgnoreMatcherUsesDockerfileIgnoreBesideDockerfileInBuildContext() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("watch-dockerfile-ignore-context"));
        Path dockerfile = context.resolve("docker/Dockerfile");
        Files.createDirectories(dockerfile.getParent());
        Files.writeString(dockerfile, "FROM scratch");
        Files.writeString(context.resolve(".dockerignore"), "root-only.txt\n");
        Files.writeString(dockerfile.resolveSibling("Dockerfile.dockerignore"), "dockerfile-only.txt\n");
        Path rootOnly = Files.writeString(context.resolve("root-only.txt"), "root");
        Path dockerfileOnly = Files.writeString(context.resolve("dockerfile-only.txt"), "specific");

        var ignored = DockerBuildContextArchive.watchIgnoreMatcher(context, dockerfile);

        assertTrue(!ignored.test(rootOnly));
        assertTrue(ignored.test(dockerfileOnly));
    }

    @Test
    void normalizesDotSegmentsInIgnorePatterns() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("dot-segment-ignore-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "./secret\n");
        Files.writeString(context.resolve("secret"), "excluded\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(!entries.contains("secret"));
    }

    @Test
    void retainsDirectoryWhenOnlyItsChildrenAreIgnored() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("empty-directory-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Path assets = Files.createDirectory(context.resolve("assets"));
        Files.writeString(assets.resolve("logo.svg"), "excluded\n");
        Files.writeString(context.resolve(".dockerignore"), "assets/*\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(entries.contains("assets/"));
        assertTrue(!entries.contains("assets/logo.svg"));
    }

    @Test
    void treatsIndentedHashAsPatternRatherThanComment() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("indented-hash-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "  #secret\n");
        Files.writeString(context.resolve("#secret"), "excluded\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(!entries.contains("#secret"));
    }

    @Test
    void questionMarkMatchesOneSupplementaryUnicodeScalar() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("unicode-question-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        String emojiFile = "😀.txt";
        Files.writeString(context.resolve(".dockerignore"), "?.txt\n");
        Files.writeString(context.resolve(emojiFile), "excluded\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(!entries.contains(emojiFile));
    }

    @Test
    void characterClassMatchesSupplementaryUnicodeScalarRange() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("unicode-class-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "[😀-😃].txt\n");
        Files.writeString(context.resolve("😃.txt"), "excluded\n");
        Files.writeString(context.resolve("a.txt"), "included\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(!entries.contains("😃.txt"));
        assertTrue(entries.contains("a.txt"));
    }

    @Test
    void honorsEscapedClosingBracketInCharacterClass() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Path context = Files.createDirectory(temporaryDirectory.resolve("escaped-class-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "report[\\]].txt\n");
        Files.writeString(context.resolve("report].txt"), "excluded\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(!entries.contains("report].txt"));
    }

    @Test
    void treatsEscapedHyphenAsCharacterClassMember() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Path context = Files.createDirectory(temporaryDirectory.resolve("escaped-hyphen-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "report[\\-].txt\n");
        Files.writeString(context.resolve("report-.txt"), "excluded\n");
        Files.writeString(context.resolve("reporta.txt"), "included\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(!entries.contains("report-.txt"));
        assertTrue(entries.contains("reporta.txt"));
    }

    @Test
    void descendingCharacterClassRangeMatchesNothingAndNegatedRangeMatchesEverything() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("descending-range-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "[z-a].txt\n[^z-a].neg\n");
        Files.writeString(context.resolve("z.txt"), "included\n");
        Files.writeString(context.resolve("a.txt"), "included\n");
        Files.writeString(context.resolve("z.neg"), "excluded\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(entries.contains("z.txt"));
        assertTrue(entries.contains("a.txt"));
        assertTrue(!entries.contains("z.neg"));
    }

    @Test
    void traversesIgnoredDirectoryWhenGlobCanReincludeAChild() throws Exception {
        Path context = Files.createDirectory(temporaryDirectory.resolve("reinclude-glob-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(context.resolve(".dockerignore"), "foo\n!f*/bar\n");
        Path ignoredDirectory = Files.createDirectory(context.resolve("foo"));
        Files.writeString(ignoredDirectory.resolve("bar"), "re-included\n");

        Set<String> entries = readEntries(context, dockerfile);

        assertTrue(entries.contains("foo/bar"));
    }

    private static Set<String> readEntries(Path context, Path dockerfile) throws Exception {
        Set<String> entries = new HashSet<>();
        try (DockerBuildContextArchive archive = DockerBuildContextArchive.create(context, dockerfile);
             TarArchiveInputStream tar = new TarArchiveInputStream(archive.stream())) {
            for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry())
                entries.add(entry.getName());
        }
        return entries;
    }

    @Test
    void preservesNamedPipesOnUnix() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Path context = Files.createDirectory(temporaryDirectory.resolve("fifo-context"));
        Path dockerfile = Files.writeString(context.resolve("Dockerfile"), "FROM scratch\n");
        Path fifo = context.resolve("pipe");
        Process process = new ProcessBuilder("mkfifo", fifo.toString()).start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());

        try (DockerBuildContextArchive archive = DockerBuildContextArchive.create(context, dockerfile);
             TarArchiveInputStream tar = new TarArchiveInputStream(archive.stream())) {
            boolean foundFifo = false;
            for (var entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
                if (entry.getName().equals("pipe")) {
                    assertEquals(org.apache.commons.compress.archivers.tar.TarArchiveEntry.LF_FIFO,
                            entry.getLinkFlag());
                    foundFifo = true;
                }
            }
            assertTrue(foundFifo);
        }
    }
}
