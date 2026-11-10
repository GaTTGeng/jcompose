package io.github.gattgeng.jcompose.engine;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerArchiveExtractionTest {
    @TempDir Path tempDirectory;

    @Test
    void extractsHardLinksAsCopies() throws IOException {
        Path destination = tempDirectory.resolve("destination");
        Files.createDirectory(destination);
        byte[] archive = archiveWithHardLink();

        ComposeService.CopyExtraction result = ComposeService.extractTar(new ByteArrayInputStream(archive), destination);

        assertEquals(20, result.bytes());
        assertTrue(result.directory());
        assertEquals("compose123", Files.readString(destination.resolve("root/source")));
        assertEquals("compose123", Files.readString(destination.resolve("root/linked")));
    }

    @Test
    void preservesUnixModesWhenExtractingContainerArchives() throws IOException {
        assumePosixFileModes();
        Path destination = Files.createDirectory(tempDirectory.resolve("mode-destination"));
        byte[] archive = archiveWithUnixModes();

        ComposeService.extractTar(new ByteArrayInputStream(archive), destination);

        assertEquals(0710, unixMode(destination.resolve("root")));
        assertEquals(0751, unixMode(destination.resolve("root/source")));
        assertEquals(0751, unixMode(destination.resolve("root/linked")));
    }

    @Test
    void preservesUnixModesWhenCreatingHostCopyArchives() throws IOException {
        assumePosixFileModes();
        Path source = Files.createDirectory(tempDirectory.resolve("mode-source"));
        Path file = Files.writeString(source.resolve("run.sh"), "#!/bin/sh\n");
        setUnixMode(source, 0710);
        setUnixMode(file, 0751);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try {
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
                ComposeService.appendTarEntry(tar, source, "source");
                tar.finish();
            }
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                assertEquals(0710, tar.getNextTarEntry().getMode() & 07777);
                assertEquals(0751, tar.getNextTarEntry().getMode() & 07777);
            }
        } finally {
            setUnixMode(source, 0700);
        }
    }

    @Test
    void extractsDirectoryContentsArchiveAtDestinationRoot() throws IOException {
        Path destination = Files.createDirectory(tempDirectory.resolve("contents"));

        ComposeService.extractTar(new ByteArrayInputStream(archiveWithCurrentDirectoryRoot()), destination, ".", "");

        assertEquals("compose123", Files.readString(destination.resolve("fixture.txt")));
        assertTrue(Files.isDirectory(destination.resolve("nested")));
        assertEquals("nested", Files.readString(destination.resolve("nested/child.txt")));
    }

    @Test
    void rebasesResolvedContainerLinkArchiveEntriesAndHardLinkTargets() throws IOException {
        Path destination = Files.createDirectory(tempDirectory.resolve("rebased"));

        ComposeService.extractTar(new ByteArrayInputStream(archiveWithHardLink()), destination, "root", "alias");

        assertEquals("compose123", Files.readString(destination.resolve("alias/source")));
        assertEquals("compose123", Files.readString(destination.resolve("alias/linked")));
        assertTrue(Files.notExists(destination.resolve("root")));
    }

    @Test
    void rejectsHardLinksOutsideTheDestination() throws IOException {
        Path destination = tempDirectory.resolve("destination");
        Files.createDirectory(destination);
        byte[] archive = archiveWithHardLink("../outside");

        assertThrows(IOException.class,
                () -> ComposeService.extractTar(new ByteArrayInputStream(archive), destination));
    }

    @Test
    void rebasesResolvedContainerLinkArchiveBackToRequestedSourceName() {
        assertEquals("fixture-link", ComposeService.rebaseArchiveRoot("fixture.txt", "fixture.txt", "fixture-link"));
        assertEquals("fixture-link/child.txt",
                ComposeService.rebaseArchiveRoot("fixture.txt/child.txt", "fixture.txt", "fixture-link"));
        assertEquals("elsewhere/child.txt",
                ComposeService.rebaseArchiveRoot("elsewhere/child.txt", "fixture.txt", "fixture-link"));
        assertEquals(".", ComposeService.rebaseArchiveRoot("./", ".", ""));
        assertEquals("child.txt", ComposeService.rebaseArchiveRoot("./child.txt", ".", ""));
    }

    @Test
    void plansContainerCopyTargetsUsingFileAndDirectorySemantics() throws IOException {
        Path missingFile = tempDirectory.resolve("copied.txt");
        ComposeService.ContainerCopyTarget newFile = ComposeService.containerCopyTarget(
                missingFile, missingFile.toString(), false, false, "source.txt", "source.txt");
        assertEquals(tempDirectory, newFile.directory());
        assertEquals("copied.txt", newFile.destinationRoot());

        Path missingDirectory = tempDirectory.resolve("copied");
        ComposeService.ContainerCopyTarget newDirectory = ComposeService.containerCopyTarget(
                missingDirectory, missingDirectory.toString(), true, false, "source", "source");
        assertEquals(tempDirectory, newDirectory.directory());
        assertEquals("copied", newDirectory.destinationRoot());

        Path existingDirectory = Files.createDirectory(tempDirectory.resolve("existing"));
        ComposeService.ContainerCopyTarget intoDirectory = ComposeService.containerCopyTarget(
                existingDirectory, existingDirectory.toString(), true, false, "source", "source");
        assertEquals(existingDirectory, intoDirectory.directory());
        assertEquals("source", intoDirectory.destinationRoot());

        Path contentsOnlyDirectory = tempDirectory.resolve("contents-only");
        ComposeService.ContainerCopyTarget contentsOnly = ComposeService.containerCopyTarget(
                contentsOnlyDirectory, contentsOnlyDirectory.toString(), true, true, ".", ".");
        assertEquals(contentsOnlyDirectory, contentsOnly.directory());
        assertEquals("", contentsOnly.destinationRoot());
        assertTrue(contentsOnly.createDirectory());

        ComposeService.ContainerCopyTarget contentsIntoDirectory = ComposeService.containerCopyTarget(
                existingDirectory, existingDirectory.toString(), true, true, ".", ".");
        assertEquals(existingDirectory, contentsIntoDirectory.directory());
        assertFalse(contentsIntoDirectory.createDirectory());

        assertThrows(IOException.class, () -> ComposeService.containerCopyTarget(
                tempDirectory.resolve("absent-parent/file"), tempDirectory.resolve("absent-parent/file").toString(),
                false, false, "source.txt", "source.txt"));
        Path existingFile = Files.writeString(tempDirectory.resolve("existing-file"), "old");
        assertThrows(IOException.class, () -> ComposeService.containerCopyTarget(
                existingFile, existingFile.toString(), true, false, "source", "source"));
        assertThrows(IOException.class, () -> ComposeService.containerCopyTarget(
                existingFile, existingFile.toString(), true, true, ".", "."));
        String assertedDirectory = tempDirectory.resolve("asserted") + "/";
        assertThrows(IOException.class, () -> ComposeService.containerCopyTarget(
                Path.of(assertedDirectory), assertedDirectory, false, false, "source.txt", "source.txt"));
    }

    @Test
    void resolvesRelativeContainerLinkTargetsAgainstSourceParent() {
        assertEquals("/data/fixture.txt", ComposeService.resolveContainerLinkTarget("/tmp/fixture-link", "../data/fixture.txt"));
        assertEquals("/data/fixture.txt", ComposeService.resolveContainerLinkTarget("/tmp/fixture-link", "/data/fixture.txt"));
    }

    @Test
    void rejectsContainerLinkMetadataWithoutTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.resolveContainerLinkTarget("/tmp/fixture-link", " "));
    }

    @Test
    void rejectsDestinationPathsWithSymlinkAncestors() throws IOException {
        Path actual = Files.createDirectory(tempDirectory.resolve("actual"));
        Path link = tempDirectory.resolve("link");
        try {
            Files.createSymbolicLink(link, actual);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return;
        }

        assertThrows(IOException.class, () -> ComposeService.ensureNoSymlinkAncestors(link.resolve("copy")));
    }

    @Test
    void followsTopLevelSourceSymlinkWhenRequested() throws IOException {
        Path target = Files.writeString(tempDirectory.resolve("target.txt"), "compose123");
        Path link = tempDirectory.resolve("source.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return;
        }

        assertEquals(link, ComposeService.resolveCopySource(link, false));
        Path copySource = ComposeService.resolveCopySource(link, true);
        assertEquals(target.toRealPath(), copySource);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            ComposeService.appendTarEntry(tar, copySource, link.getFileName().toString());
            tar.finish();
        }
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            TarArchiveEntry entry = tar.getNextTarEntry();
            assertEquals("source.txt", entry.getName());
            assertEquals("compose123", new String(tar.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void copiesSymbolicLinksWithoutFollowingThemByDefault() throws IOException {
        Path target = Files.writeString(tempDirectory.resolve("target.txt"), "compose123");
        Path link = tempDirectory.resolve("source.txt");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return;
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            assertEquals(0, ComposeService.appendTarEntry(tar, link, "source.txt"));
            tar.finish();
        }
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            TarArchiveEntry entry = tar.getNextTarEntry();
            assertTrue(entry.isSymbolicLink());
            assertEquals(target.getFileName().toString(), entry.getLinkName());
        }

        Path destination = Files.createDirectory(tempDirectory.resolve("destination"));
        ComposeService.extractTar(new ByteArrayInputStream(bytes.toByteArray()), destination);
        Path copiedLink = destination.resolve("source.txt");
        assertTrue(Files.isSymbolicLink(copiedLink));
        assertEquals(target.getFileName(), Files.readSymbolicLink(copiedLink));
    }

    @Test
    void rejectsArchiveEntriesThatTraverseAnExtractedSymbolicLink() throws IOException {
        Path destination = Files.createDirectory(tempDirectory.resolve("destination"));
        byte[] archive = archiveWithSymbolicLinkTraversal();

        assertThrows(IOException.class,
                () -> ComposeService.extractTar(new ByteArrayInputStream(archive), destination));
    }

    private static byte[] archiveWithHardLink() throws IOException {
        return archiveWithHardLink("root/source");
    }

    private static byte[] archiveWithHardLink(String target) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry directory = new TarArchiveEntry("root/", TarArchiveEntry.LF_DIR);
            tar.putArchiveEntry(directory);
            tar.closeArchiveEntry();

            byte[] contents = "compose123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            TarArchiveEntry file = new TarArchiveEntry("root/source");
            file.setSize(contents.length);
            tar.putArchiveEntry(file);
            tar.write(contents);
            tar.closeArchiveEntry();

            TarArchiveEntry hardLink = new TarArchiveEntry("root/linked", TarArchiveEntry.LF_LINK);
            hardLink.setLinkName(target);
            tar.putArchiveEntry(hardLink);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] archiveWithUnixModes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry directory = new TarArchiveEntry("root/", TarArchiveEntry.LF_DIR);
            directory.setMode(0710);
            tar.putArchiveEntry(directory);
            tar.closeArchiveEntry();

            byte[] contents = "#!/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            TarArchiveEntry file = new TarArchiveEntry("root/source");
            file.setMode(0751);
            file.setSize(contents.length);
            tar.putArchiveEntry(file);
            tar.write(contents);
            tar.closeArchiveEntry();

            TarArchiveEntry hardLink = new TarArchiveEntry("root/linked", TarArchiveEntry.LF_LINK);
            hardLink.setMode(0600);
            hardLink.setLinkName("root/source");
            tar.putArchiveEntry(hardLink);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private static void assumePosixFileModes() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
    }

    private static int unixMode(Path path) throws IOException {
        return ((Number) Files.getAttribute(path, "unix:mode")).intValue() & 07777;
    }

    private static void setUnixMode(Path path, int mode) throws IOException {
        Files.setAttribute(path, "unix:mode", mode);
    }

    private static byte[] archiveWithSymbolicLinkTraversal() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry link = new TarArchiveEntry("root/link", TarArchiveEntry.LF_SYMLINK);
            link.setLinkName("../outside");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();

            TarArchiveEntry child = new TarArchiveEntry("root/link/escape.txt");
            child.setSize(0);
            tar.putArchiveEntry(child);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return bytes.toByteArray();
    }

    private static byte[] archiveWithCurrentDirectoryRoot() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry directory = new TarArchiveEntry("./", TarArchiveEntry.LF_DIR);
            tar.putArchiveEntry(directory);
            tar.closeArchiveEntry();

            byte[] contents = "compose123".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            TarArchiveEntry file = new TarArchiveEntry("./fixture.txt");
            file.setSize(contents.length);
            tar.putArchiveEntry(file);
            tar.write(contents);
            tar.closeArchiveEntry();

            byte[] nestedContents = "nested".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            TarArchiveEntry nested = new TarArchiveEntry("./nested/child.txt");
            nested.setSize(nestedContents.length);
            tar.putArchiveEntry(nested);
            tar.write(nestedContents);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
}
