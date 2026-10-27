package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ComposeServiceReplicaTest {
    @Test
    void selectsServiceScaleBeforeDeployReplicas() {
        assertEquals(3, ComposeService.replicaCount(new ServiceDefinition("app", Map.of(
                "scale", 3,
                "deploy", Map.of("replicas", 3)))));
        assertEquals(2, ComposeService.replicaCount(new ServiceDefinition("app", Map.of(
                "deploy", Map.of("replicas", 2)))));
        assertEquals(1, ComposeService.replicaCount(new ServiceDefinition("app", Map.of())));
    }

    @Test
    void replicaCountsDoNotChangeContainerConfigurationHash() {
        ServiceDefinition one = new ServiceDefinition("app", Map.of(
                "image", "alpine", "scale", 1, "deploy", Map.of("replicas", 1)));
        ServiceDefinition three = new ServiceDefinition("app", Map.of(
                "image", "alpine", "scale", 3, "deploy", Map.of("replicas", 3)));

        assertEquals(ComposeService.configHash(one), ComposeService.configHash(three));
        assertNotEquals(ComposeService.configHash(one), ComposeService.configHash(
                new ServiceDefinition("app", Map.of("image", "busybox", "scale", 1))));
    }
}
