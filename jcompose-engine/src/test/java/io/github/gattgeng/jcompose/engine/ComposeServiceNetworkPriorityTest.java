package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposeServiceNetworkPriorityTest {
    @Test
    void serviceMacAddressUsesNetworkWithHighestPriority() {
        Map<String, Object> networks = new LinkedHashMap<>();
        networks.put("frontend", Map.of("priority", 0));
        networks.put("backend", Map.of("priority", 100));
        ServiceDefinition service = new ServiceDefinition("app", Map.of("networks", networks));

        assertEquals("backend", ComposeService.serviceMacAddressNetwork(service));
    }

    @Test
    void serviceMacAddressUsesFirstNetworkWhenPrioritiesTie() {
        Map<String, Object> networks = new LinkedHashMap<>();
        networks.put("frontend", Map.of("priority", 10));
        networks.put("backend", Map.of("priority", 10));
        ServiceDefinition service = new ServiceDefinition("app", Map.of("networks", networks));

        assertEquals("frontend", ComposeService.serviceMacAddressNetwork(service));
    }
}
