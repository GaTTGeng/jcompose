package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.api.ComposeRestartOptions;
import io.github.gattgeng.jcompose.api.ComposeRunOptions;
import io.github.gattgeng.jcompose.api.ComposeTopOptions;
import io.github.gattgeng.jcompose.api.IComposeService;
import io.github.gattgeng.jcompose.api.ILogConsumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeServiceDockerIntegrationTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void requireDockerBridgeNetworkSupport() {
        String projectName = "jcompose-probe-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, Path.of(".").toAbsolutePath());
        try (var client = DockerClientFactory.create(context)) {
            client.pingCmd().exec();
            var network = client.createNetworkCmd().withName(projectName + "_probe").withDriver("bridge").exec();
            client.removeNetworkCmd(network.getId()).exec();
        } catch (java.io.IOException | RuntimeException unavailable) {
            Assumptions.abort("Docker Engine with bridge network support is not available: " + unavailable.getMessage());
        }
    }

    @Test
    void upPsAndDownManageAComposeServiceAndItsNetwork() throws Exception {
        String projectName = "jcompose-it-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);

        Files.createDirectories(directory.resolve("data"));
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "sleep 60"]
                    init: true
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
            String runContainer = service.runAsync(context, "app", ComposeRunOptions.builder()
                    .command(java.util.List.of("sh", "-c", "sleep 60"))
                    .noDeps(true).detach(true).tty(false).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                try {
                    assertTrue(Boolean.TRUE.equals(client.inspectContainerCmd(containers.getFirst().id())
                            .exec().getHostConfig().getInit()));
                    assertTrue(Boolean.TRUE.equals(client.inspectContainerCmd(runContainer)
                            .exec().getHostConfig().getInit()));
                } finally {
                    client.removeContainerCmd(runContainer).withForce(true).withRemoveVolumes(false).exec();
                }
            }
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }

        assertTrue(service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS).isEmpty());
    }

    @Test
    void topReturnsDockerProcessRowsForSelectedService() throws Exception {
        String projectName = "jcompose-top-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  web:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                  worker:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

            var rows = service.topAsync(context, ComposeTopOptions.builder().services(java.util.List.of("worker")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(1, rows.size());
            assertEquals("worker", rows.getFirst().service());
            assertEquals("1", rows.getFirst().replica());
            assertFalse(rows.getFirst().titles().isEmpty());
            assertFalse(rows.getFirst().processes().isEmpty());
            assertEquals(rows.getFirst().titles().size(), rows.getFirst().processes().getFirst().size());
            assertTrue(service.topAsync(context, ComposeTopOptions.builder().services(java.util.List.of()).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS).isEmpty());
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void restartingDependencyRestartsOnlyDependentsOptedInByComposeFile() throws Exception {
        String projectName = "jcompose-restart-deps-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                    depends_on:
                      db:
                        condition: service_started
                        restart: true
                  observer:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                    depends_on:
                      db:
                        condition: service_started
                        restart: false
                  db:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var containers = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                var startedAt = new java.util.HashMap<String, String>();
                for (var container : containers) {
                    startedAt.put(container.service(), client.inspectContainerCmd(container.id()).exec().getState().getStartedAt());
                }

                service.restartAsync(context, ComposeRestartOptions.builder().services(java.util.List.of("db")).build())
                        .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);

                var after = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
                for (var container : after) {
                    String currentStartedAt = client.inspectContainerCmd(container.id()).exec().getState().getStartedAt();
                    if ("observer".equals(container.service())) assertEquals(startedAt.get("observer"), currentStartedAt);
                    else assertFalse(startedAt.get(container.service()).equals(currentStartedAt),
                            container.service() + " should have been restarted");
                }
            }
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void upRecreationOfDependencyRestartsOnlyDependentsOptedInByComposeFile() throws Exception {
        String projectName = "jcompose-up-restart-deps-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Path composeFile = directory.resolve("compose.yaml");
        Files.writeString(composeFile, """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                    depends_on:
                      db:
                        condition: service_started
                        restart: true
                  observer:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                    depends_on:
                      db:
                        condition: service_started
                        restart: false
                  db:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var before = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                var startedAt = new java.util.HashMap<String, String>();
                var containerIds = new java.util.HashMap<String, String>();
                for (var container : before) {
                    containerIds.put(container.service(), container.id());
                    startedAt.put(container.service(), client.inspectContainerCmd(container.id()).exec().getState().getStartedAt());
                }

                Files.writeString(composeFile, """
                        services:
                          app:
                            image: busybox:1.36
                            command: ["sleep", "60"]
                            depends_on:
                              db:
                                condition: service_started
                                restart: true
                          observer:
                            image: busybox:1.36
                            command: ["sleep", "60"]
                            depends_on:
                              db:
                                condition: service_started
                                restart: false
                          db:
                            image: busybox:1.36
                            command: ["sleep", "61"]
                        """);
                service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

                var after = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
                for (var container : after) {
                    String currentStartedAt = client.inspectContainerCmd(container.id()).exec().getState().getStartedAt();
                    if ("observer".equals(container.service())) assertEquals(startedAt.get("observer"), currentStartedAt);
                    else assertFalse(startedAt.get(container.service()).equals(currentStartedAt),
                            container.service() + " should have been recreated or restarted");
                }
                assertFalse(containerIds.get("db").equals(after.stream()
                        .filter(container -> "db".equals(container.service())).findFirst().orElseThrow().id()));
            }
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void optionalDependencyFailureDoesNotPreventDependentServiceFromStarting() throws Exception {
        String projectName = "jcompose-optional-dep-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                    depends_on:
                      job:
                        condition: service_completed_successfully
                        required: false
                      health:
                        condition: service_healthy
                        required: false
                  job:
                    image: busybox:1.36
                    command: ["sh", "-c", "exit 7"]
                  health:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

            var containers = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var app = containers.stream().filter(container -> "app".equals(container.service())).findFirst().orElseThrow();
            assertEquals("running", app.state());
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void upDoesNotCollectLogsForServicesWithAttachDisabled() throws Exception {
        String projectName = "jcompose-attach-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  quiet:
                    image: busybox:1.36
                    command: ["sh", "-c", "echo quiet-output; sleep 1"]
                    attach: false
                  visible:
                    image: busybox:1.36
                    command: ["sh", "-c", "echo visible-output; sleep 1"]
                """);

        CopyOnWriteArrayList<String> logServices = new CopyOnWriteArrayList<>();
        ILogConsumer consumer = new ILogConsumer() {
            @Override public void onLog(String serviceName, String message, boolean standardError) {
                logServices.add(serviceName);
            }
            @Override public void onLogComplete(String serviceName) { }
            @Override public void onStatus(String serviceName, String message) { }
        };
        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, io.github.gattgeng.jcompose.api.ComposeUpOptions.builder()
                            .detach(false).logConsumer(consumer).build())
                    .get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

            assertTrue(logServices.contains("visible"));
            assertFalse(logServices.contains("quiet"));
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
