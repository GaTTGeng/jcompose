package io.github.gattgeng.jcompose.engine;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
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
}