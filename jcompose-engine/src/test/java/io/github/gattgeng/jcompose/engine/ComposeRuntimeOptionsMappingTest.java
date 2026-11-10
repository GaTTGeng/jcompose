package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Isolation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeRuntimeOptionsMappingTest {
    @Test
    void mapsCgroupNamespaceAndIsolationOptions() {
        HostConfig host = HostConfig.newHostConfig();

        ComposeService.applyRuntimeOptions(host, Map.of("cgroup", "private", "isolation", "process"));

        assertEquals("private", host.getCgroupnsMode());
        assertEquals(Isolation.PROCESS, host.getIsolation());
    }

    @Test
    void rejectsUnsupportedCgroupAndIsolationValues() {
        assertThrows(IllegalArgumentException.class, () -> ComposeService.applyRuntimeOptions(
                HostConfig.newHostConfig(), Map.of("cgroup", "shared")));
        assertThrows(IllegalArgumentException.class, () -> ComposeService.applyRuntimeOptions(
                HostConfig.newHostConfig(), Map.of("isolation", "container")));
    }

    @Test
    void mapsServiceAndDeployPidsLimitsToDockerHostConfig() {
        HostConfig serviceLimit = HostConfig.newHostConfig();
        ComposeService.applyResourceConfig(serviceLimit, Map.of("pids_limit", 128));
        assertEquals(128L, serviceLimit.getPidsLimit());

        HostConfig deployLimit = HostConfig.newHostConfig();
        ComposeService.applyResourceConfig(deployLimit, Map.of("deploy", Map.of("resources",
                Map.of("limits", Map.of("pids", 64)))));
        assertEquals(64L, deployLimit.getPidsLimit());

        HostConfig unlimited = HostConfig.newHostConfig();
        ComposeService.applyResourceConfig(unlimited, Map.of("pids_limit", -1));
        assertEquals(-1L, unlimited.getPidsLimit());
    }

    @Test
    void mapsMemorySwappinessToDockerHostConfig() {
        HostConfig host = HostConfig.newHostConfig();

        ComposeService.applyResourceConfig(host, Map.of("mem_swappiness", 42));

        assertEquals(42L, host.getMemorySwappiness());
    }
}
