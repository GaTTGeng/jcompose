package io.github.gattgeng.jcompose.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeBuildMappingTest {
    @Test
    void identifiesRemoteBuildContextsWithoutTreatingWindowsPathsAsUrls() {
        assertTrue(ComposeService.isRemoteBuildContext("https://github.com/example/project.git#main:service"));
        assertTrue(ComposeService.isRemoteBuildContext("git://github.com/example/project.git"));
        assertTrue(ComposeService.isRemoteBuildContext("ssh://git@github.com/example/project.git"));
        assertTrue(ComposeService.isRemoteBuildContext("git@github.com:example/project.git"));
        assertFalse(ComposeService.isRemoteBuildContext("../service"));
        assertFalse(ComposeService.isRemoteBuildContext("C:\\projects\\service"));
    }

    @Test
    void mapsBuildExtraHostsFromComposeMappingForm() {
        Map<String, Object> configured = new LinkedHashMap<>();
        configured.put("gateway", "host-gateway");
        configured.put("database", "192.0.2.10");

        assertEquals(Set.of("gateway:host-gateway", "database:192.0.2.10"),
                ComposeService.buildExtraHosts(configured));
    }

    @Test
    void preservesBuildExtraHostsListForm() {
        assertEquals(Set.of("gateway:host-gateway", "database:192.0.2.10"),
                ComposeService.buildExtraHosts(List.of("gateway:host-gateway", "database:192.0.2.10")));
    }

    @Test
    void includesPrimaryImageAndAllDistinctBuildTags() {
        assertEquals(List.of("example/app:latest", "example/app:stable", "example/app:release"),
                List.copyOf(ComposeService.buildTags("example/app:latest",
                        List.of("example/app:stable", "example/app:release", "example/app:latest"))));
    }
}
