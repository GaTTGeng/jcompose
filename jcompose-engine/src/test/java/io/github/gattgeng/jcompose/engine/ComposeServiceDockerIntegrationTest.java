package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.api.IComposeService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeServiceDockerIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void upPsAndDownManageAComposeServiceAndItsNetwork() throws Exception {
        String projectName = "jcompose-it-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        try (var client = DockerClientFactory.create(context)) {
            try {
                client.pingCmd().exec();
                var probeNetwork = client.createNetworkCmd()
                        .withName(projectName + "_probe")
                        .withDriver("bridge")
                        .exec();
                client.removeNetworkCmd(probeNetwork.getId()).exec();
            } catch (RuntimeException unavailable) {
                Assumptions.abort("Docker Engine with bridge network support is not available: " + unavailable.getMessage());
            }
        }

        Files.createDirectories(directory.resolve("data"));
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "sleep 60"]
                    networks: [frontend]
                    volumes: ["./data:/data:ro"]
                networks:
                  frontend: {}
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var containers = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);

            assertEquals(1, containers.size());
            assertEquals("app", containers.getFirst().service());
            assertTrue(containers.getFirst().networks().contains(projectName + "_frontend"));
            assertTrue(containers.getFirst().mounts().stream().anyMatch(mount -> "/data".equals(mount.destination())
                    && "ro".equals(mount.mode()) && !mount.readWrite()));
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }

        assertTrue(service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS).isEmpty());
    }
}
