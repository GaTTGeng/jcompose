package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProject;
import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComposeServiceStopTimeoutTest {
    @Test
    void usesServiceGracePeriodUnlessAnOperationTimeoutIsSet() {
        ServiceDefinition service = new ServiceDefinition("app", Map.of("stop_grace_period", "1.5s"));
        ComposeProject project = new ComposeProject(null, Map.of("app", service), List.of(), List.of(), List.of(), List.of(), Map.of());

        assertEquals(2, ComposeService.stopTimeout("app", project, null));
        assertEquals(7, ComposeService.stopTimeout("app", project, 7));
        assertNull(ComposeService.stopTimeout("app", null, null));
    }
}
