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
}
