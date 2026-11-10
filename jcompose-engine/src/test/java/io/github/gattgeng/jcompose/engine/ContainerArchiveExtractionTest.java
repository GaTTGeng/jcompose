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
    void rejectsHardLinksOutsideTheDestination() throws IOException {
        Path destination = tempDirectory.resolve("destination");
        Files.createDirectory(destination);
        byte[] archive = archiveWithHardLink("../outside");

        assertThrows(IOException.class,
                () -> ComposeService.extractTar(new ByteArrayInputStream(archive), destination));
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
}
