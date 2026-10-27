package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComposeServiceCredentialSpecTest {
    @Test
    void mapsFileAndRegistryFormsToDockerSecurityOptions() {
        assertEquals("credentialspec=file://gmsa.json", ComposeService.credentialSpecSecurityOption(
                new ServiceDefinition("app", Map.of("credential_spec", Map.of("file", "gmsa.json")))));
        assertEquals("credentialspec=registry://gmsa-name", ComposeService.credentialSpecSecurityOption(
                new ServiceDefinition("app", Map.of("credential_spec", Map.of("registry", "gmsa-name")))));
    }

    @Test
    void doesNotMapSwarmConfigFormToContainerSecurityOptions() {
        assertNull(ComposeService.credentialSpecSecurityOption(
                new ServiceDefinition("app", Map.of("credential_spec", Map.of("config", "swarm-config")))));
    }
}
