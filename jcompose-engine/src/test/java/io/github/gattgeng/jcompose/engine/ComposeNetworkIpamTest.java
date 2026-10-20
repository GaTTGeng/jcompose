package io.github.gattgeng.jcompose.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposeNetworkIpamTest {
    @Test
    void serializesComposeIpamOptionsAndAuxiliaryAddressesForDockerEngine() throws Exception {
        var ipam = new ComposeNetworkIpam(Map.of("driver-option", "value"));
        ipam.withDriver("custom-ipam");
        ipam.withConfig(new ComposeNetworkIpam.Config(Map.of("reserved", "172.30.200.50"))
                .withSubnet("172.30.200.0/24"));

        var mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsString(ipam));

        assertEquals("custom-ipam", json.get("Driver").asText());
        assertEquals("value", json.path("Options").path("driver-option").asText());
        assertEquals("172.30.200.50", json.path("Config").get(0).path("AuxiliaryAddresses")
                .path("reserved").asText());
        assertEquals("172.30.200.0/24", json.path("Config").get(0).path("Subnet").asText());
    }
}
