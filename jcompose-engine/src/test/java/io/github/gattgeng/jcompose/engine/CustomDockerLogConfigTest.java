package io.github.gattgeng.jcompose.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DockerClientConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustomDockerLogConfigTest {
    @Test
    void serializesPluginDriverNameForDockerEngine() throws Exception {
        var config = HostConfig.newHostConfig().withLogConfig(
                new CustomDockerLogConfig("example-plugin", Map.of("endpoint", "tcp://logs:9000")));

        JsonNode json = DockerClientConfig.getDefaultObjectMapper().readTree(
                DockerClientConfig.getDefaultObjectMapper().writeValueAsBytes(config));

        assertEquals("example-plugin", json.path("LogConfig").path("Type").asText());
        assertEquals("tcp://logs:9000", json.path("LogConfig").path("Config").path("endpoint").asText());
    }
}
