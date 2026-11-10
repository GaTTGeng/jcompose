package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerClientFactoryTest {
    @Test
    void parsesBase64EncodedArchivePathStatAndDetectsSymbolicLinks() {
        String json = "{\"name\":\"fixture-link\",\"mode\":134217728,\"linkTarget\":\"../data/fixture.txt\"}";
        String header = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        DockerClientFactory.ContainerArchivePathStat stat = DockerClientFactory.parseContainerArchivePathStat(header);

        assertTrue(stat.symbolicLink());
        assertEquals("../data/fixture.txt", stat.linkTarget());
    }

    @Test
    void identifiesNonSymbolicArchivePathStat() {
        String json = "{\"name\":\"fixture.txt\",\"mode\":420,\"linkTarget\":\"\"}";
        String header = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        var stat = DockerClientFactory.parseContainerArchivePathStat(header);
        assertFalse(stat.symbolicLink());
        assertFalse(stat.directory());
        assertEquals("fixture.txt", stat.name());
    }

    @Test
    void identifiesDirectoryArchivePathStat() {
        String json = "{\"name\":\"data\",\"mode\":2147483648,\"linkTarget\":\"\"}";
        String header = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        assertTrue(DockerClientFactory.parseContainerArchivePathStat(header).directory());
    }

    @Test
    void rejectsInvalidArchivePathStat() {
        assertThrows(IllegalArgumentException.class,
                () -> DockerClientFactory.parseContainerArchivePathStat("not-base64"));
        String incomplete = Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,
                () -> DockerClientFactory.parseContainerArchivePathStat(incomplete));
    }
}
