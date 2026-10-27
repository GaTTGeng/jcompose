package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.exception.NotFoundException;
import io.github.gattgeng.jcompose.api.ComposeEvent;
import io.github.gattgeng.jcompose.api.ComposeCopyOptions;
import io.github.gattgeng.jcompose.api.ComposeCommitOptions;
import io.github.gattgeng.jcompose.api.ComposeDownOptions;
import io.github.gattgeng.jcompose.api.ComposeExecOptions;
import io.github.gattgeng.jcompose.api.ComposeExportOptions;
import io.github.gattgeng.jcompose.api.ComposeEventsOptions;
import io.github.gattgeng.jcompose.api.ComposeListOptions;
import io.github.gattgeng.jcompose.api.ComposePauseOptions;
import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.api.ComposeRestartOptions;
import io.github.gattgeng.jcompose.api.ComposeRunOptions;
import io.github.gattgeng.jcompose.api.ComposeScaleOptions;
import io.github.gattgeng.jcompose.api.ComposeStartOptions;
import io.github.gattgeng.jcompose.api.ComposeStopOptions;
import io.github.gattgeng.jcompose.api.ComposeTopOptions;
import io.github.gattgeng.jcompose.api.ComposeUpOptions;
import io.github.gattgeng.jcompose.api.IComposeService;
import io.github.gattgeng.jcompose.api.ILogConsumer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void appliesOnFailureRetryCountToCreatedContainer() throws Exception {
        String projectName = "jcompose-restart-retries-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    restart: on-failure:5
                """);

        IComposeService service = new ComposeService();
        try {
            service.createAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                var containers = client.listContainersCmd().withShowAll(true)
                        .withLabelFilter(java.util.Map.of("com.docker.compose.project", projectName,
                                "com.docker.compose.service", "app"))
                        .exec();
                assertEquals(5, client.inspectContainerCmd(containers.getFirst().getId()).exec()
                        .getHostConfig().getRestartPolicy().getMaximumRetryCount());
            }
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void copiesAndExportsFilesThroughDockerArchiveEndpoints() throws Exception {
        String projectName = "jcompose-archive-transfer-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        String contents = "archive-data";
        Path sourcePath = directory.resolve("fixture.txt");
        Path copiedDirectory = directory.resolve("copied");
        Path exportPath = directory.resolve("filesystem.tar");
        Path stoppedExportPath = directory.resolve("stopped-filesystem.tar");
        Files.writeString(sourcePath, contents);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "mkdir -p /data && sleep 300"]
                    deploy:
                      replicas: 2
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var intoContainer = service.copyAsync(context, ComposeCopyOptions.builder()
                    .source(sourcePath.toString()).destination("app:/data").all(true).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(contents.length() * 2L, intoContainer.bytesCopied());
            assertEquals(0, intoContainer.exitCode());

            var outOfContainer = service.copyAsync(context, ComposeCopyOptions.builder()
                    .source("app:/data").destination(copiedDirectory.toString()).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(outOfContainer.directory());
            assertEquals(contents.length(), outOfContainer.bytesCopied());
            assertEquals(contents, Files.readString(copiedDirectory.resolve("data/fixture.txt")));

            Path secondReplicaDirectory = directory.resolve("copied-replica-two");
            service.copyAsync(context, ComposeCopyOptions.builder().source("app:/data")
                            .destination(secondReplicaDirectory.toString()).index(2).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(contents, Files.readString(secondReplicaDirectory.resolve("data/fixture.txt")));

            service.exportAsync(context, ComposeExportOptions.builder().service("app")
                            .outputPath(exportPath.toString()).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(archiveContains(exportPath, "data/fixture.txt"));

            service.stopAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var copiedToStoppedContainer = service.copyAsync(context, ComposeCopyOptions.builder()
                    .source(sourcePath.toString()).destination("app:/data").all(true).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(contents.length() * 2L, copiedToStoppedContainer.bytesCopied());

            Path stoppedCopy = directory.resolve("stopped-copy");
            service.copyAsync(context, ComposeCopyOptions.builder().source("app:/data")
                            .destination(stoppedCopy.toString()).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(contents, Files.readString(stoppedCopy.resolve("data/fixture.txt")));

            service.exportAsync(context, ComposeExportOptions.builder().service("app")
                            .outputPath(stoppedExportPath.toString()).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(Files.size(stoppedExportPath) > 0);
        } finally {
            service.downAsync(context, ComposeDownOptions.builder().removeVolumes(true).build())
                    .get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static boolean archiveContains(Path archivePath, String expectedPath) throws Exception {
        try (var input = Files.newInputStream(archivePath);
             var archive = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.getName().replace('\\', '/').replaceFirst("^/+", "").endsWith(expectedPath)) return true;
            }
            return false;
        }
    }

    @Test
    void executesPostStartAndPreStopHooks() throws Exception {
        String projectName = "jcompose-hooks-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Path hookDirectory = Files.createDirectories(directory.resolve("hooks"));
        Path events = hookDirectory.resolve("events");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                    volumes: ["./hooks:/hooks"]
                    environment:
                      BASE: inherited
                    post_start:
                      - command: [sh, -c, 'echo post:$HOOK:$BASE > /hooks/events']
                        environment: [HOOK=started, BASE]
                    pre_stop:
                      - command: [sh, -c, 'echo pre:$HOOK >> /hooks/events']
                        environment:
                          HOOK: stopped
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("post:started:inherited\n", Files.readString(events));

            service.stopAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("post:started:inherited\npre:stopped\n", Files.readString(events));
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void runsPreStartInitContainerWithServiceVolumesBeforeStartingService() throws Exception {
        String projectName = "jcompose-pre-start-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Path hookDirectory = Files.createDirectories(directory.resolve("pre-start"));
        Path events = hookDirectory.resolve("events");
        Path allow = hookDirectory.resolve("allow");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'test -f /shared/ready && sleep 300']
                    volumes: ["./pre-start:/shared"]
                    pre_start:
                      - image: busybox:1.36
                        command: [sh, -c, 'if [ ! -f /shared/allow ]; then echo failed >> /shared/events; exit 1; fi; echo initialized >> /shared/events; touch /shared/ready']
                """);

        IComposeService service = new ComposeService();
        try {
            assertThrows(ExecutionException.class, () -> service.upAsync(context, null)
                    .get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS));
            try (var client = DockerClientFactory.create(context)) {
                var failedHooks = client.listContainersCmd().withShowAll(true)
                        .withLabelFilter(java.util.Map.of("com.docker.compose.project", projectName,
                                "com.docker.compose.hook", "pre_start"))
                        .exec();
                assertEquals(1, failedHooks.size());
            }

            Files.writeString(allow, "retry\n");
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("failed\ninitialized\n", Files.readString(events));
            try (var client = DockerClientFactory.create(context)) {
                var hookContainers = client.listContainersCmd().withShowAll(true)
                        .withLabelFilter(java.util.Map.of("com.docker.compose.project", projectName,
                                "com.docker.compose.hook", "pre_start"))
                        .exec();
                assertTrue(hookContainers.isEmpty());
            }
            service.stopAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            service.startAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            service.restartAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("failed\ninitialized\n", Files.readString(events));
            try (var client = DockerClientFactory.create(context)) {
                var containers = client.listContainersCmd().withShowAll(true)
                        .withLabelFilter(java.util.Map.of("com.docker.compose.project", projectName,
                                "com.docker.compose.service", "app"))
                        .exec();
                assertEquals(1, containers.size());
                assertTrue(containers.getFirst().getStatus().contains("Up"));
            }
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void runExecutesPostStartHookForOneOffContainer() throws Exception {
        String projectName = "jcompose-run-hooks-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Path hookDirectory = Files.createDirectories(directory.resolve("run-hooks"));
        Path marker = hookDirectory.resolve("started");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                    volumes: ["./run-hooks:/hooks"]
                    post_start:
                      - command: [sh, -c, 'echo started > /hooks/started']
                """);

        IComposeService service = new ComposeService();
        String containerId = service.runAsync(context, "app", ComposeRunOptions.builder()
                        .command(java.util.List.of("sh", "-c", "sleep 300"))
                        .noDeps(true).detach(true).tty(false).build())
                .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        try {
            assertEquals("started\n", Files.readString(marker));
        } finally {
            try (var client = DockerClientFactory.create(context)) {
                client.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(false).exec();
            }
        }
    }

    @Test
    void runCreatesAnonymousVolumeMounts() throws Exception {
        String projectName = "jcompose-run-volume-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                    volumes: ["/cache"]
                """);

        IComposeService service = new ComposeService();
        String containerId = service.runAsync(context, "app", ComposeRunOptions.builder()
                        .noDeps(true).detach(true).tty(false).build())
                .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        try (var client = DockerClientFactory.create(context)) {
            var mounts = client.inspectContainerCmd(containerId).exec().getMounts();
            assertTrue(mounts.stream().anyMatch(mount -> "/cache".equals(mount.getDestination().getPath())
                    && mount.getName() != null && !mount.getName().isBlank()));
        } finally {
            try (var client = DockerClientFactory.create(context)) {
                client.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
            }
        }
    }

    @Test
    void appliesLongFormVolumeNoCopyToServiceAndRunContainers() throws Exception {
        String projectName = "jcompose-volume-nocopy-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                    volumes:
                      - type: volume
                        source: data
                        target: /data
                        volume:
                          nocopy: true
                volumes:
                  data: {}
                """);

        IComposeService service = new ComposeService();
        String runContainerId = null;
        try {
            service.createAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                var containers = client.listContainersCmd().withShowAll(true)
                        .withLabelFilter(java.util.Map.of("com.docker.compose.project", projectName,
                                "com.docker.compose.service", "app"))
                        .exec();
                assertNoCopyVolume(client.inspectContainerCmd(containers.getFirst().getId()).exec().getHostConfig());
            }

            runContainerId = service.runAsync(context, "app", ComposeRunOptions.builder()
                            .noDeps(true).detach(true).tty(false).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                assertNoCopyVolume(client.inspectContainerCmd(runContainerId).exec().getHostConfig());
            }
        } finally {
            if (runContainerId != null) try (var client = DockerClientFactory.create(context)) {
                client.removeContainerCmd(runContainerId).withForce(true).withRemoveVolumes(true).exec();
            }
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static void assertNoCopyVolume(com.github.dockerjava.api.model.HostConfig config) {
        assertTrue(config.getMounts().stream().anyMatch(mount -> "/data".equals(mount.getTarget())
                && mount.getVolumeOptions() != null && Boolean.TRUE.equals(mount.getVolumeOptions().getNoCopy())));
    }

    @Test
    void mountsLongFormVolumeSubpathAndPreservesExternalVolumeOnDown() throws Exception {
        String projectName = "jcompose-volume-subpath-" + UUID.randomUUID().toString().substring(0, 8);
        String volumeName = projectName + "-shared";
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        String seedContainerId = null;
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                    volumes:
                      - type: volume
                        source: data
                        target: /data
                        volume:
                          subpath: logs/app
                volumes:
                  data:
                    external: true
                    name: %s
                """.formatted(volumeName));

        IComposeService service = new ComposeService();
        try {
            try (var client = DockerClientFactory.create(context)) {
                client.createVolumeCmd().withName(volumeName).exec();
                seedContainerId = client.createContainerCmd("busybox:1.36")
                        .withCmd("sh", "-c", "mkdir -p /seed/logs/app && echo subpath-ok > /seed/logs/app/probe")
                        .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig()
                                .withBinds(new com.github.dockerjava.api.model.Bind(volumeName,
                                        new com.github.dockerjava.api.model.Volume("/seed"))))
                        .exec().getId();
                client.startContainerCmd(seedContainerId).exec();
                CompletableFuture<Integer> seeded = new CompletableFuture<>();
                client.waitContainerCmd(seedContainerId).exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<com.github.dockerjava.api.model.WaitResponse>() {
                    @Override public void onNext(com.github.dockerjava.api.model.WaitResponse response) {
                        seeded.complete(response.getStatusCode());
                    }
                    @Override public void onError(Throwable error) { seeded.completeExceptionally(error); }
                    @Override public void onComplete() { }
                });
                assertEquals(0, seeded.get(30, TimeUnit.SECONDS));
                client.removeContainerCmd(seedContainerId).withForce(true).exec();
                seedContainerId = null;
            }
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var result = service.execAsync(context, "app", ComposeExecOptions.builder()
                            .command(java.util.List.of("cat", "/data/probe")).tty(false).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("subpath-ok", result.standardOutput().trim());
            service.downAsync(context, ComposeDownOptions.builder().removeVolumes(true).build())
                    .get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                assertTrue(client.listVolumesCmd().exec().getVolumes().stream()
                        .anyMatch(volume -> volumeName.equals(volume.getName())));
            }
        } finally {
            if (seedContainerId != null) try (var client = DockerClientFactory.create(context)) {
                client.removeContainerCmd(seedContainerId).withForce(true).exec();
            }
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                client.removeVolumeCmd(volumeName).exec();
            }
        }
    }

    @Test
    void scaleStartsDependenciesUnlessNoDepsIsSet() throws Exception {
        String projectName = "jcompose-scale-deps-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                    depends_on: [db]
                  db:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                """);

        IComposeService service = new ComposeService();
        try {
            service.scaleAsync(context, ComposeScaleOptions.builder().services(java.util.Map.of("app", 1)).build())
                    .get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

            var containers = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(containers.stream().anyMatch(container -> "db".equals(container.service())));
            assertTrue(containers.stream().anyMatch(container -> "app".equals(container.service())));
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void upNoDepsDoesNotStartDependencies() throws Exception {
        String projectName = "jcompose-up-nodeps-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                    depends_on: [db]
                  db:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, ComposeUpOptions.builder().services(java.util.List.of("app"))
                            .noDeps(true).build())
                    .get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

            var containers = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(containers.stream().anyMatch(container -> "app".equals(container.service())));
            assertFalse(containers.stream().anyMatch(container -> "db".equals(container.service())));
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void scaleNoDepsDoesNotStartDependencies() throws Exception {
        String projectName = "jcompose-scale-nodeps-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                    depends_on: [db]
                  db:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                """);

        IComposeService service = new ComposeService();
        try {
            service.scaleAsync(context, ComposeScaleOptions.builder().services(java.util.Map.of("app", 1))
                            .noDeps(true).build())
                    .get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

            var containers = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(containers.stream().anyMatch(container -> "app".equals(container.service())));
            assertFalse(containers.stream().anyMatch(container -> "db".equals(container.service())));
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
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
                    uts: host
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
                    assertEquals("host", client.inspectContainerCmd(containers.getFirst().id())
                            .exec().getHostConfig().getUtSMode());
                    assertTrue(Boolean.TRUE.equals(client.inspectContainerCmd(runContainer)
                            .exec().getHostConfig().getInit()));
                    assertEquals("host", client.inspectContainerCmd(runContainer)
                            .exec().getHostConfig().getUtSMode());
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
    void downWithVolumesRemovesAnonymousAndDeclaredVolumes() throws Exception {
        String projectName = "jcompose-down-volumes-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: [sh, -c, 'sleep 300']
                    volumes:
                      - data:/data
                      - /cache
                volumes:
                  data: {}
                """);

        IComposeService service = new ComposeService();
        java.util.Set<String> createdVolumes;
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                String containerId = service.psAsync(context, null).get(30, TimeUnit.SECONDS).getFirst().id();
                createdVolumes = client.inspectContainerCmd(containerId).exec().getMounts().stream()
                        .map(com.github.dockerjava.api.command.InspectContainerResponse.Mount::getName)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
            }
            assertTrue(createdVolumes.contains(projectName + "_data"));
            assertEquals(2, createdVolumes.size());

            service.downAsync(context, ComposeDownOptions.builder().removeVolumes(true).build())
                    .get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);

            try (var client = DockerClientFactory.create(context)) {
                var remainingVolumes = client.listVolumesCmd().exec().getVolumes().stream()
                        .map(com.github.dockerjava.api.command.InspectVolumeResponse::getName)
                        .collect(java.util.stream.Collectors.toSet());
                assertTrue(remainingVolumes.stream().noneMatch(createdVolumes::contains));
            }
        } finally {
            service.downAsync(context, ComposeDownOptions.builder().removeVolumes(true).build())
                    .get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void listDerivesProjectStatusAndHonorsAll() throws Exception {
        String projectName = "jcompose-list-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  first:
                    image: busybox:1.36
                    command: ["sleep", "300"]
                  second:
                    image: busybox:1.36
                    command: ["sleep", "300"]
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var running = service.listAsync(null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("running", running.stream().filter(stack -> projectName.equals(stack.name()))
                    .findFirst().orElseThrow().status());

            service.pauseAsync(context, ComposePauseOptions.builder().services(java.util.List.of("first")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var paused = service.listAsync(null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("unknown", paused.stream().filter(stack -> projectName.equals(stack.name()))
                    .findFirst().orElseThrow().status());
            service.unpauseAsync(context, ComposePauseOptions.builder().services(java.util.List.of("first")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);

            service.stopAsync(context, ComposeStopOptions.builder().services(java.util.List.of("first"))
                            .timeoutSeconds(1).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var partial = service.listAsync(null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("partial", partial.stream().filter(stack -> projectName.equals(stack.name()))
                    .findFirst().orElseThrow().status());

            service.stopAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var defaultList = service.listAsync(null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertFalse(defaultList.stream().anyMatch(stack -> projectName.equals(stack.name())));
            var all = service.listAsync(ComposeListOptions.builder().all(true).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("exited", all.stream().filter(stack -> projectName.equals(stack.name()))
                    .findFirst().orElseThrow().status());
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void runExecutesPreStartHooksBeforeStartingTheOneOffContainer() throws Exception {
        String projectName = "jcompose-run-pre-start-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        var marker = directory.resolve("run-pre-start.marker");
        Files.deleteIfExists(marker);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "sleep 60"]
                    volumes: ["./:/workspace"]
                    pre_start:
                      - command: ["sh", "-c", "echo ready > /workspace/run-pre-start.marker"]
                """);

        IComposeService service = new ComposeService();
        String containerId = null;
        try {
            containerId = service.runAsync(context, "app", ComposeRunOptions.builder()
                    .noDeps(true).detach(true).tty(false).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertEquals("ready", Files.readString(marker).trim());
        } finally {
            try (var client = DockerClientFactory.create(context)) {
                if (containerId != null) client.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(false).exec();
            }
            Files.deleteIfExists(marker);
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void runPublishesServicePortsOnlyWhenRequested() throws Exception {
        String projectName = "jcompose-run-ports-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "sleep 60"]
                    ports: ["127.0.0.1:0:8080"]
                """);

        IComposeService service = new ComposeService();
        String defaultRun = null;
        String servicePortsRun = null;
        String explicitPublishRun = null;
        String nonInteractiveRun = null;
        try {
            defaultRun = service.runAsync(context, "app", ComposeRunOptions.builder()
                    .noDeps(true).detach(true).tty(false).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            servicePortsRun = service.runAsync(context, "app", ComposeRunOptions.builder()
                    .noDeps(true).detach(true).tty(false).servicePorts(true).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            explicitPublishRun = service.runAsync(context, "app", ComposeRunOptions.builder()
                    .noDeps(true).detach(true).tty(false).publish(java.util.List.of("127.0.0.1:0:8081/udp")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            nonInteractiveRun = service.runAsync(context, "app", ComposeRunOptions.builder()
                    .noDeps(true).detach(true).tty(false).interactive(false).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);

            try (var client = DockerClientFactory.create(context)) {
                var defaultBindings = client.inspectContainerCmd(defaultRun).exec().getHostConfig().getPortBindings();
                var requestedBindings = client.inspectContainerCmd(servicePortsRun).exec().getHostConfig().getPortBindings();
                var explicitBindings = client.inspectContainerCmd(explicitPublishRun).exec().getHostConfig().getPortBindings();
                assertTrue(client.inspectContainerCmd(defaultRun).exec().getConfig().getStdinOpen());
                assertFalse(client.inspectContainerCmd(nonInteractiveRun).exec().getConfig().getStdinOpen());
                assertTrue(defaultBindings == null || defaultBindings.getBindings().isEmpty());
                assertFalse(requestedBindings == null || requestedBindings.getBindings().isEmpty());
                assertEquals(1, explicitBindings.getBindings().size());
                assertTrue(explicitBindings.getBindings().keySet().stream().anyMatch(port -> "8081/udp".equals(port.toString())));
            }
        } finally {
            try (var client = DockerClientFactory.create(context)) {
                if (defaultRun != null) client.removeContainerCmd(defaultRun).withForce(true).withRemoveVolumes(false).exec();
                if (servicePortsRun != null) client.removeContainerCmd(servicePortsRun).withForce(true).withRemoveVolumes(false).exec();
                if (explicitPublishRun != null) client.removeContainerCmd(explicitPublishRun).withForce(true).withRemoveVolumes(false).exec();
                if (nonInteractiveRun != null) client.removeContainerCmd(nonInteractiveRun).withForce(true).withRemoveVolumes(false).exec();
            }
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void runForwardsStandardInputWhenInteractive() throws Exception {
        String projectName = "jcompose-run-stdin-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                """);

        IComposeService service = new ComposeService();
        InputStream originalInput = System.in;
        String containerId = null;
        try {
            System.setIn(new ByteArrayInputStream("input-from-stdin\n".getBytes(StandardCharsets.UTF_8)));
            containerId = service.runAsync(context, "app", ComposeRunOptions.builder()
                    .command(java.util.List.of("sh", "-c", "read value && test \"$value\" = input-from-stdin"))
                    .noDeps(true).detach(false).tty(false).interactive(true).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);

            try (var client = DockerClientFactory.create(context)) {
                var state = client.inspectContainerCmd(containerId).exec().getState();
                assertEquals("exited", state.getStatus());
                assertEquals(0L, state.getExitCodeLong());
            }
        } finally {
            System.setIn(originalInput);
            try (var client = DockerClientFactory.create(context)) {
                if (containerId != null) client.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(false).exec();
            }
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void linkAliasesResolveOnSharedNetworkAndChangesRecreateTarget() throws Exception {
        String projectName = "jcompose-links-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Path composeFile = directory.resolve("compose.yaml");
        Files.writeString(composeFile, """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "sleep 60"]
                    links: [db:database]
                    networks: [frontend]
                  db:
                    image: busybox:1.36
                    command: ["sh", "-c", "sleep 60"]
                    networks: [frontend]
                networks:
                  frontend: {}
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var before = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var beforeDb = before.stream().filter(container -> "db".equals(container.service())).findFirst().orElseThrow();
            var inspected = service.execAsync(context, "app", ComposeExecOptions.builder()
                            .command(java.util.List.of("ping", "-c", "1", "-W", "1", "database")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(inspected.succeeded(), inspected.standardError());

            Files.writeString(composeFile, """
                    services:
                      app:
                        image: busybox:1.36
                        command: ["sh", "-c", "sleep 60"]
                        links: [db:database-next]
                        networks: [frontend]
                      db:
                        image: busybox:1.36
                        command: ["sh", "-c", "sleep 60"]
                        networks: [frontend]
                    networks:
                      frontend: {}
                    """);
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

            var after = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var afterDb = after.stream().filter(container -> "db".equals(container.service())).findFirst().orElseThrow();
            assertFalse(beforeDb.id().equals(afterDb.id()), "changing a link alias must recreate its target service");
            var afterPing = service.execAsync(context, "app", ComposeExecOptions.builder()
                            .command(java.util.List.of("ping", "-c", "1", "-W", "1", "database-next")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(afterPing.succeeded(), afterPing.standardError());
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void appliesExtendedOptionsToPrimaryNetworkBeforeStartingService() throws Exception {
        String projectName = "jcompose-primary-endpoint-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Path composeFile = directory.resolve("compose.yaml");
        Files.writeString(composeFile, """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "sleep 60"]
                    networks:
                      default:
                        ipv4_address: 172.31.100.10
                        interface_name: eth2
                        link_local_ips: [169.254.100.10]
                        driver_opts:
                          com.docker.network.endpoint.sysctls: net.ipv4.conf.IFNAME.log_martians=1
                        gw_priority: 1
                networks:
                  default:
                    ipam:
                      config:
                        - subnet: 172.31.100.0/24
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var containers = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var app = containers.stream().filter(container -> "app".equals(container.service())).findFirst().orElseThrow();
            try (var client = DockerClientFactory.create(context)) {
                var network = client.inspectContainerCmd(app.id()).exec().getNetworkSettings().getNetworks()
                        .get(projectName + "_default");
                assertEquals("172.31.100.10", network.getIpAddress());
            }
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void createsProjectNetworkWithIpamAuxiliaryAddresses() throws Exception {
        String projectName = "jcompose-ipam-aux-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Path composeFile = directory.resolve("compose.yaml");
        Files.writeString(composeFile, """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "sleep 60"]
                    networks:
                      default:
                        ipv4_address: 172.31.101.10
                networks:
                  default:
                    ipam:
                      config:
                        - subnet: 172.31.101.0/24
                          aux_addresses:
                            reserved: 172.31.101.50
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var containers = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var app = containers.stream().filter(container -> "app".equals(container.service())).findFirst().orElseThrow();
            try (var client = DockerClientFactory.create(context)) {
                var network = client.inspectContainerCmd(app.id()).exec().getNetworkSettings().getNetworks()
                        .get(projectName + "_default");
                assertEquals("172.31.101.10", network.getIpAddress());
            }
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void externalLinksResolveOnAnExplicitlySharedNetwork() throws Exception {
        String projectName = "jcompose-external-links-" + UUID.randomUUID().toString().substring(0, 8);
        String networkName = projectName + "_shared";
        String externalName = projectName + "_legacy";
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        String externalContainerId;
        String networkId;
        try (var client = DockerClientFactory.create(context)) {
            networkId = client.createNetworkCmd().withName(networkName).withDriver("bridge").exec().getId();
            externalContainerId = client.createContainerCmd("busybox:1.36").withName(externalName)
                    .withCmd("sh", "-c", "sleep 300").exec().getId();
            client.startContainerCmd(externalContainerId).exec();
            client.connectToNetworkCmd().withNetworkId(networkId).withContainerId(externalContainerId).exec();
        }
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "sleep 300"]
                    external_links: ["%s:legacy"]
                    networks: [shared]
                networks:
                  shared:
                    external: true
                    name: %s
                """.formatted(externalName, networkName));

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            var result = service.execAsync(context, "app", ComposeExecOptions.builder()
                            .command(java.util.List.of("ping", "-c", "1", "-W", "1", "legacy")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertTrue(result.succeeded(), result.standardError());
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                client.removeContainerCmd(externalContainerId).withForce(true).exec();
                client.removeNetworkCmd(networkId).exec();
            }
        }
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
    void eventsSignalReadyAndFilterLifecycleEventsByService() throws Exception {
        String projectName = "jcompose-events-" + UUID.randomUUID().toString().substring(0, 8);
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
        CompletableFuture<Void> subscribed = new CompletableFuture<>();
        CompletableFuture<Flow.Subscription> subscription = new CompletableFuture<>();
        CompletableFuture<ComposeEvent> stoppedEvent = new CompletableFuture<>();
        var observed = new CopyOnWriteArrayList<ComposeEvent>();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            service.eventsAsync(context, ComposeEventsOptions.builder().services(java.util.List.of("worker"))
                            .onSubscribed(() -> subscribed.complete(null)).build())
                    .subscribe(new Flow.Subscriber<>() {
                        @Override public void onSubscribe(Flow.Subscription value) {
                            subscription.complete(value);
                            value.request(Long.MAX_VALUE);
                        }

                        @Override public void onNext(ComposeEvent event) {
                            observed.add(event);
                            if (event.type().equals("container")
                                    && java.util.Set.of("die", "kill", "stop").contains(event.action()))
                                stoppedEvent.complete(event);
                        }

                        @Override public void onError(Throwable failure) {
                            subscribed.completeExceptionally(failure);
                            stoppedEvent.completeExceptionally(failure);
                        }
                        @Override public void onComplete() {
                            stoppedEvent.completeExceptionally(new AssertionError("Docker event stream ended unexpectedly."));
                        }
                    });

            subscription.get(30, TimeUnit.SECONDS);
            subscribed.get(30, TimeUnit.SECONDS);
            service.stopAsync(context, ComposeStopOptions.builder().services(java.util.List.of("worker")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            ComposeEvent event = stoppedEvent.get(30, TimeUnit.SECONDS);

            assertEquals("worker", event.service());
            assertTrue(observed.stream().allMatch(item -> "worker".equals(item.service())));
        } finally {
            Flow.Subscription activeSubscription = subscription.getNow(null);
            if (activeSubscription != null) activeSubscription.cancel();
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void eventsKeepProjectNetworkEventsWithoutAServiceFilter() throws Exception {
        String projectName = "jcompose-project-events-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  web:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                """);

        IComposeService service = new ComposeService();
        CompletableFuture<Void> subscribed = new CompletableFuture<>();
        CompletableFuture<Flow.Subscription> subscription = new CompletableFuture<>();
        CompletableFuture<ComposeEvent> networkEvent = new CompletableFuture<>();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            service.eventsAsync(context, ComposeEventsOptions.builder().onSubscribed(() -> subscribed.complete(null)).build())
                    .subscribe(new Flow.Subscriber<>() {
                        @Override public void onSubscribe(Flow.Subscription value) {
                            subscription.complete(value);
                            value.request(Long.MAX_VALUE);
                        }

                        @Override public void onNext(ComposeEvent event) {
                            if (event.type().equals("network")
                                    && java.util.Set.of("destroy", "remove").contains(event.action()))
                                networkEvent.complete(event);
                        }

                        @Override public void onError(Throwable failure) {
                            subscribed.completeExceptionally(failure);
                            networkEvent.completeExceptionally(failure);
                        }
                        @Override public void onComplete() {
                            networkEvent.completeExceptionally(new AssertionError("Docker event stream ended unexpectedly."));
                        }
                    });

            subscription.get(30, TimeUnit.SECONDS);
            subscribed.get(30, TimeUnit.SECONDS);
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
            ComposeEvent event = networkEvent.get(30, TimeUnit.SECONDS);

            assertEquals("network", event.type());
            assertNull(event.service());
        } finally {
            Flow.Subscription activeSubscription = subscription.getNow(null);
            if (activeSubscription != null) activeSubscription.cancel();
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
    void healthyDependencyTimeoutNamesDependentAndDependencyServices() throws Exception {
        String projectName = "jcompose-dependency-timeout-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                    depends_on:
                      database:
                        condition: service_healthy
                  database:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                    healthcheck:
                      test: ["CMD-SHELL", "sleep 10"]
                      interval: 30s
                      timeout: 15s
                      retries: 5
                """);

        IComposeService service = new ComposeService();
        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> service.upAsync(context, io.github.gattgeng.jcompose.api.ComposeUpOptions.builder()
                                    .dependencyWaitTimeoutSeconds(2).build())
                            .get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS));
            Throwable cause = failure.getCause();
            while (cause.getCause() != null) cause = cause.getCause();

            assertTrue(cause instanceof TimeoutException, "Expected timeout, got: " + cause);
            assertTrue(cause.getMessage().contains("app"));
            assertTrue(cause.getMessage().contains("database"));
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void commitCreatesTaggedImageThroughDockerEngine() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String projectName = "jcompose-managed-commit-" + suffix;
        String reference = "jcompose-managed-commit-image-" + suffix + ":latest";
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sleep", "300"]
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            service.execAsync(context, "app", ComposeExecOptions.builder()
                            .command(java.util.List.of("sh", "-c", "echo committed > /marker")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);

            String imageReference = service.commitAsync(context, ComposeCommitOptions.builder()
                            .service("app").reference(reference).author("jcompose tests")
                            .message("managed commit").pause(true).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);

            assertEquals(reference, imageReference);
            try (var client = DockerClientFactory.create(context)) {
                var image = client.inspectImageCmd(reference).exec();
                assertTrue(image.getId() != null && !image.getId().isBlank());
            }
        } finally {
            try {
                service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                try (var client = DockerClientFactory.create(context)) {
                    try {
                        client.removeImageCmd(reference).withForce(true).exec();
                    } catch (NotFoundException ignored) { }
                }
            }
        }
    }

    @Test
    void startPropagatesDependencyDockerFailureBeforeStartingDependentService() throws Exception {
        int occupiedPort;
        try (var portProbe = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            occupiedPort = portProbe.getLocalPort();
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Path appDirectory = directory.resolve("app");
        Path blockerDirectory = directory.resolve("blocker");
        Files.createDirectories(appDirectory);
        Files.createDirectories(blockerDirectory);
        String projectName = "jcompose-dependency-start-failure-" + suffix;
        String blockerProjectName = "jcompose-dependency-start-blocker-" + suffix;
        ComposeProjectContext context = new ComposeProjectContext(projectName, appDirectory);
        ComposeProjectContext blockerContext = new ComposeProjectContext(blockerProjectName, blockerDirectory);
        Files.writeString(appDirectory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sleep", "300"]
                    depends_on: [database]
                  database:
                    image: busybox:1.36
                    command: ["sleep", "300"]
                    ports:
                      - "127.0.0.1:%d:80"
                """.formatted(occupiedPort));
        Files.writeString(blockerDirectory.resolve("compose.yaml"), """
                services:
                  blocker:
                    image: busybox:1.36
                    command: ["sleep", "300"]
                    ports:
                      - "127.0.0.1:%d:80"
                """.formatted(occupiedPort));

        IComposeService service = new ComposeService();
        IComposeService blockerService = new ComposeService();
        try {
            service.upAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            service.stopAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            blockerService.upAsync(blockerContext, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> service.startAsync(context, ComposeStartOptions.builder().services(java.util.List.of("app")).build())
                            .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(failure.getCause() != null);
            Throwable cause = failure.getCause();
            while (cause.getCause() != null) cause = cause.getCause();
            assertFalse(cause instanceof TimeoutException, "The Docker start failure must propagate instead of timing out.");

            var containers = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            var app = containers.stream().filter(container -> "app".equals(container.service())).findFirst().orElseThrow();
            assertEquals("exited", app.state());
        } finally {
            blockerService.downAsync(blockerContext, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void upWaitTimeoutBoundsSelectedServiceReadinessSeparatelyFromDependencyTimeout() throws Exception {
        String projectName = "jcompose-up-wait-timeout-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sleep", "60"]
                    healthcheck:
                      test: ["CMD-SHELL", "sleep 10"]
                      interval: 30s
                      timeout: 15s
                      retries: 5
                """);

        IComposeService service = new ComposeService();
        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> service.upAsync(context, ComposeUpOptions.builder().waitForCompletion(true)
                                    .waitTimeoutSeconds(2).dependencyWaitTimeoutSeconds(30).build())
                            .get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS));
            Throwable cause = failure.getCause();
            while (cause.getCause() != null) cause = cause.getCause();

            assertTrue(cause instanceof TimeoutException, "Expected timeout, got: " + cause);
            assertTrue(cause.getMessage().contains("app"));
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

    @Test
    void startAttachToFiltersOutputStreams() throws Exception {
        String projectName = "jcompose-start-attach-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: busybox:1.36
                    command: ["sh", "-c", "echo stdout-marker; echo stderr-marker >&2"]
                """);

        CopyOnWriteArrayList<String> output = new CopyOnWriteArrayList<>();
        ILogConsumer consumer = new ILogConsumer() {
            @Override public void onLog(String serviceName, String message, boolean standardError) {
                output.add((standardError ? "stderr:" : "stdout:") + message);
            }
            @Override public void onLogComplete(String serviceName) { }
            @Override public void onStatus(String serviceName, String message) { }
        };
        IComposeService service = new ComposeService();
        try {
            service.createAsync(context, null).get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            service.startAsync(context, ComposeStartOptions.builder().attach(consumer)
                            .attachTo(java.util.List.of("stdout")).build())
                    .get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);

            assertTrue(output.stream().anyMatch(line -> line.contains("stdout-marker")));
            assertFalse(output.stream().anyMatch(line -> line.contains("stderr-marker")));
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void upWaitUsesHealthcheckInheritedFromImage() throws Exception {
        String projectName = "jcompose-wait-image-health-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("Dockerfile"), """
                FROM busybox:1.36
                HEALTHCHECK --interval=1s --timeout=5s --retries=10 CMD ["sh", "-c", "sleep 3; exit 0"]
                CMD ["sh", "-c", "sleep 60"]
                """);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    build: .
                    pull_policy: build
                """);

        IComposeService service = new ComposeService();
        try {
            service.upAsync(context, io.github.gattgeng.jcompose.api.ComposeUpOptions.builder()
                            .waitForCompletion(true).dependencyWaitTimeoutSeconds(30).build())
                    .get(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);

            try (var client = DockerClientFactory.create(context)) {
                var container = service.psAsync(context, null).get(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)
                        .getFirst();
                var health = client.inspectContainerCmd(container.id()).exec().getState().getHealth();
                assertTrue(health != null && "healthy".equalsIgnoreCase(health.getStatus()));
            }
        } finally {
            service.downAsync(context, null).get(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void buildUsesInlineDockerfileAndLocalContext() throws Exception {
        String projectName = "jcompose-inline-build-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeProjectContext context = new ComposeProjectContext(projectName, directory);
        Files.writeString(directory.resolve("payload.txt"), "inline-build-context");
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: %s-app
                    build:
                      context: .
                      dockerfile_inline: |
                        FROM busybox:1.36
                        COPY payload.txt /payload.txt
                """.formatted(projectName));

        IComposeService service = new ComposeService();
        try {
            service.buildAsync(context, null).get(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
            try (var client = DockerClientFactory.create(context)) {
                var image = client.inspectImageCmd(projectName + "-app").exec();
                assertTrue(image.getId() != null && !image.getId().isBlank());
            }
        } finally {
            try (var client = DockerClientFactory.create(context)) {
                client.removeImageCmd(projectName + "-app").withForce(true).exec();
            }
        }
    }
}
