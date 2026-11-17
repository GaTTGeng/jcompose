package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.core.DockerClientConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComposeVolumeMappingTest {
    @Test
    void mapsVolumeMetadataFromTheDockerResponseIncludingUnmodeledFields() throws Exception {
        InspectVolumeResponse source = DockerClientConfig.getDefaultObjectMapper().readValue("""
                {
                  "Name": "demo_data",
                  "Driver": "local",
                  "Mountpoint": "/var/lib/docker/volumes/demo_data/_data",
                  "Labels": {"com.docker.compose.project": "demo", "com.docker.compose.volume": "data"},
                  "Scope": "local",
                  "Options": {"type": "tmpfs", "device": "tmpfs"},
                  "CreatedAt": "2026-11-17T09:30:00+08:00"
                }
                """, InspectVolumeResponse.class);

        var volume = ComposeService.volumeSummary(source);

        assertEquals("demo_data", volume.name());
        assertEquals("local", volume.driver());
        assertEquals("/var/lib/docker/volumes/demo_data/_data", volume.mountpoint());
        assertEquals(Map.of("com.docker.compose.project", "demo", "com.docker.compose.volume", "data"), volume.labels());
        assertEquals("local", volume.scope());
        assertEquals(Map.of("type", "tmpfs", "device", "tmpfs"), volume.options());
        assertEquals("2026-11-17T09:30:00+08:00", volume.createdAt());
    }

    @Test
    void leavesMissingOptionalMetadataUnset() throws Exception {
        InspectVolumeResponse source = DockerClientConfig.getDefaultObjectMapper().readValue("""
                {"Name": "demo_data", "Driver": "local"}
                """, InspectVolumeResponse.class);

        var volume = ComposeService.volumeSummary(source);

        assertEquals("demo_data", volume.name());
        assertEquals("local", volume.driver());
        assertEquals(Map.of(), volume.labels());
        assertNull(volume.mountpoint());
        assertNull(volume.scope());
        assertNull(volume.options());
        assertNull(volume.createdAt());
    }
}
