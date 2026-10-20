package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComposeHealthcheckMappingTest {
    @Test
    void mapsStartIntervalToDockerHealthcheck() {
        ServiceDefinition service = new ServiceDefinition("app", Map.of(
                "healthcheck", Map.of(
                        "test", List.of("CMD", "curl", "--fail", "http://localhost/health"),
                        "start_interval", "250ms")));

        assertEquals(250_000_000L, ComposeService.serviceHealthcheck(service).getStartInterval());
    }

    @Test
    void mapsStringTestToCmdShellForm() {
        ServiceDefinition service = new ServiceDefinition("app", Map.of(
                "healthcheck", Map.of("test", "curl -f http://localhost/health")));

        assertEquals(List.of("CMD-SHELL", "curl -f http://localhost/health"),
                ComposeService.serviceHealthcheck(service).getTest());
    }

    @Test
    void disabledHealthcheckOverridesStringTest() {
        ServiceDefinition service = new ServiceDefinition("app", Map.of(
                "healthcheck", Map.of("test", "curl -f http://localhost/health", "disable", true)));

        assertEquals(List.of("NONE"), ComposeService.serviceHealthcheck(service).getTest());
    }

    @Test
    void omitsHealthcheckWhenTestIsMissing() {
        ServiceDefinition service = new ServiceDefinition("app", Map.of(
                "healthcheck", Map.of("interval", "30s")));

        assertNull(ComposeService.serviceHealthcheck(service));
    }
}
