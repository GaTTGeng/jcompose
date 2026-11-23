package io.github.gattgeng.jcompose.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DockerClientConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComposeContainerMountMappingTest {
    @Test
    void mapsMountTypesFromTheDockerContainerList() throws Exception {
        List<Container> response = DockerClientConfig.getDefaultObjectMapper().readValue("""
                [{"Id":"container-id", "Mounts":[
                  {"Type":"bind", "Source":"/host", "Destination":"/data", "RW":false},
                  {"Type":"tmpfs", "Destination":"/memory", "RW":true},
                  {"Type":"volume", "Name":"demo_data", "Destination":"/volume", "RW":true},
                  {"Destination":"/unknown", "RW":true}
                ]}]
                """, new TypeReference<List<Container>>() { });

        var mounts = ComposeService.containerResult(response.getFirst(), "demo").mounts();

        assertEquals("bind", mounts.get(0).type());
        assertEquals("tmpfs", mounts.get(1).type());
        assertEquals("volume", mounts.get(2).type());
        assertNull(mounts.get(3).type());
    }
}
