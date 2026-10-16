package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposeHealthcheckMappingTest {
    @Test
    void mapsStartIntervalToDockerHealthcheck() {
        ServiceDefinition service = new ServiceDefinition("app", Map.of(
                "healthcheck", Map.of(
                        "test", List.of("CMD", "curl", "--fail", "http://localhost/health"),
                        "start_interval", "250ms")));

        assertEquals(250_000_000L, ComposeService.serviceHealthcheck(service).getStartInterval());
    }
}
