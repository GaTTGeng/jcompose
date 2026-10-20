package io.github.gattgeng.jcompose.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposeEndpointConfigTest {
    @Test
    void serializesComposeNetworkEndpointOptionsUsingDockerEngineFieldNames() throws Exception {
        var endpoint = new ComposeEndpointConfig()
                .withInterfaceName("eth1")
                .withDriverOpts(Map.of("com.example.option", "enabled"))
                .withGwPriority(10)
                .withComposeIpamConfig(new ComposeEndpointConfig.ComposeEndpointIpam()
                        .withIpv4Address("10.20.0.5")
                        .withLinkLocalIps(List.of("169.254.1.10")));

        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsBytes(endpoint));

        assertEquals("eth1", json.path("InterfaceName").asText());
        assertEquals("enabled", json.path("DriverOpts").path("com.example.option").asText());
        assertEquals(10, json.path("GwPriority").asInt());
        assertEquals("10.20.0.5", json.path("IPAMConfig").path("IPv4Address").asText());
        assertEquals("169.254.1.10", json.path("IPAMConfig").path("LinkLocalIPs").get(0).asText());
    }
}
