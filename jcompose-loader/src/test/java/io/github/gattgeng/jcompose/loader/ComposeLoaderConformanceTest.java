package io.github.gattgeng.jcompose.loader;

import io.github.gattgeng.jcompose.api.ComposeProject;
import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Maps the ComposeSharp loader conformance fixtures to jcompose's normalized raw model. */
class ComposeLoaderConformanceTest {
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @TempDir
    Path temporaryDirectory;

    @Test
    void mapsBuildDefinitionFixture() throws IOException {
        ComposeProject project = loadFixture("build-definition");
        ServiceDefinition app = project.services().get("app");
        Map<?, ?> build = (Map<?, ?>) app.build();

        assertEquals("build-definition", project.sourceFile().getParent().getFileName().toString());
        assertEquals("example/app:build", app.image());
        assertEquals("./app", build.get("context"));
        assertEquals("Dockerfile.test", build.get("dockerfile"));
        assertEquals("test", ((Map<?, ?>) build.get("args")).get("BUILD_MODE"));
        assertEquals("runtime", build.get("target"));
        assertEquals(List.of("example/app:test"), build.get("tags"));
        assertEquals("enabled", ((Map<?, ?>) build.get("labels")).get("build.label"));
        assertEquals(List.of("linux/amd64"), build.get("platforms"));
        assertTrue((Boolean) build.get("pull"));
        assertTrue((Boolean) build.get("no_cache"));
    }

    @Test
    void resolvesCommandAndEnvironmentFixture() throws IOException {
        ServiceDefinition app = loadFixture("command-environment").services().get("app");

        assertEquals("example/app:1.2.3", app.image());
        assertEquals(List.of("dotnet", "app.dll"), app.configuration().get("command"));
        assertEquals(List.of("/bin/sh", "-c"), app.configuration().get("entrypoint"));
        Map<?, ?> environment = (Map<?, ?>) app.configuration().get("environment");
        assertEquals("Server=database;Database=app", environment.get("CONNECTION_STRING"));
        assertEquals("Testing", environment.get("ASPNETCORE_ENVIRONMENT"));
        assertEquals("enabled", environment.get("FEATURE_FLAG"));
    }

    @Test
    void mapsNetworkAndStorageFixture() throws IOException {
        ComposeProject project = loadFixture("network-storage");
        ServiceDefinition app = project.services().get("app");

        assertEquals(List.of("app-data"), project.volumes());
        assertEquals(List.of("frontend"), project.networks());
        assertEquals(List.of("127.0.0.1:8080:8081/tcp", "9090:9091/udp"), app.configuration().get("ports"));
        assertEquals(List.of("app-data:/var/lib/app"), app.configuration().get("volumes"));
        assertEquals(List.of("frontend"), app.configuration().get("networks"));
        assertEquals("api", ((Map<?, ?>) app.configuration().get("labels")).get("com.example.component"));
    }

    @Test
    void mapsServiceLifecycleFixture() throws IOException {
        ServiceDefinition app = loadFixture("service-lifecycle").services().get("app");
        Map<?, ?> healthcheck = (Map<?, ?>) app.configuration().get("healthcheck");

        assertEquals("on-failure:4", app.configuration().get("restart"));
        assertEquals(List.of("debug", "tests"), app.profiles());
        assertEquals(List.of("CMD-SHELL", "curl --fail http://localhost:8081/health || exit 1"), healthcheck.get("test"));
        assertEquals("30s", healthcheck.get("interval"));
        assertEquals("5s", healthcheck.get("timeout"));
        assertEquals(3, healthcheck.get("retries"));
        assertEquals("10s", healthcheck.get("start_period"));
    }

    @Test
    void mapsProjectResourcesFixture() throws IOException {
        ComposeProject project = loadFixture("service-resources");
        ServiceDefinition app = project.services().get("app");
        Map<?, ?> deploy = (Map<?, ?>) app.configuration().get("deploy");
        Map<?, ?> resources = (Map<?, ?>) deploy.get("resources");
        Map<?, ?> limits = (Map<?, ?>) resources.get("limits");
        Map<?, ?> reservations = (Map<?, ?>) resources.get("reservations");

        assertEquals(List.of("app-secret"), project.secrets());
        assertEquals(List.of("app-config"), project.configs());
        assertEquals(List.of("app-secret"), app.configuration().get("secrets"));
        assertEquals(List.of("app-config"), app.configuration().get("configs"));
        assertEquals(2, deploy.get("replicas"));
        assertEquals("512M", limits.get("memory"));
        assertEquals(2, limits.get("cpus"));
        assertEquals("128M", reservations.get("memory"));
    }

    @Test
    void mergesOverrideFixtureFieldsAndResources() throws IOException {
        Path directory = copyFixture("merged-loader");
        ComposeProject project = new ComposeFileLoader().loadMerged(directory,
                List.of("compose.yaml", "compose.override.yaml"));
        ServiceDefinition app = project.services().get("app");

        assertEquals("example/app:override", app.image());
        assertEquals(List.of("run", "--verbose"), app.configuration().get("command"));
        Map<?, ?> environment = (Map<?, ?>) app.configuration().get("environment");
        assertEquals("base", environment.get("BASE_ONLY"));
        assertEquals("override", environment.get("SHARED"));
        assertEquals("overlay", environment.get("OVERLAY_ONLY"));
        assertEquals("frontend", ((Map<?, ?>) app.configuration().get("labels")).get("com.example.tier"));
        assertEquals("api", ((Map<?, ?>) app.configuration().get("labels")).get("com.example.component"));
        assertEquals(List.of("8080:80", "9090:81"), app.configuration().get("ports"));
        assertEquals(List.of("override-data:/var/lib/app", "cache:/cache", "logs:/var/log/app"),
                app.configuration().get("volumes"));
        assertEquals(List.of("frontend", "backend"), app.configuration().get("networks"));
        assertTrue(project.services().containsKey("worker"));
        assertEquals(List.of("app-data", "cache", "override-data", "logs"), project.volumes());
        assertEquals(List.of("frontend", "backend"), project.networks());
        assertEquals(List.of("app-config", "app-config-override"), project.configs());
        assertEquals(List.of("app-secret", "app-secret-override"), project.secrets());
    }

    @Test
    void replacesWindowsBindMountByContainerTarget() throws IOException {
        ComposeProject project = loadMerged("C:\\base:/data:ro", "D:\\override:/data:rw");

        assertEquals(List.of("D:\\override:/data:rw"), project.services().get("app").configuration().get("volumes"));
    }

    @Test
    void keepsDistinctWindowsContainerTargets() throws IOException {
        ComposeProject project = loadMerged("data:C:\\app", "logs:C:\\logs");

        assertEquals(List.of("data:C:\\app", "logs:C:\\logs"), project.services().get("app").configuration().get("volumes"));
    }

    @Test
    void keepsDistinctTargetOnlyWindowsVolumes() throws IOException {
        ComposeProject project = loadMerged("C:\\data", "D:\\data");

        assertEquals(List.of("C:\\data", "D:\\data"), project.services().get("app").configuration().get("volumes"));
    }

    @Test
    void replacesSingleLetterNamedVolumeByContainerTarget() throws IOException {
        ComposeProject project = loadMerged("a:/data", "b:/data");

        assertEquals(List.of("b:/data"), project.services().get("app").configuration().get("volumes"));
    }

    private ComposeProject loadFixture(String scenario) throws IOException {
        Path directory = copyFixture(scenario);
        if (Files.isRegularFile(directory.resolve(".env.example")))
            Files.copy(directory.resolve(".env.example"), directory.resolve(".env"));
        return new ComposeFileLoader().load(directory, "compose.yaml");
    }

    private Path copyFixture(String scenario) throws IOException {
        Path source = FIXTURES.resolve(scenario);
        Path destination = temporaryDirectory.resolve(scenario);
        Files.createDirectories(destination);
        try (var paths = Files.walk(source)) {
            for (Path file : paths.filter(Files::isRegularFile).toList())
                Files.copy(file, destination.resolve(source.relativize(file)));
        }
        return destination;
    }

    private ComposeProject loadMerged(String baseVolume, String overrideVolume) throws IOException {
        Path directory = temporaryDirectory.resolve("volume-merge");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app:
                    image: example/app
                    volumes:
                      - '%s'
                """.formatted(baseVolume));
        Files.writeString(directory.resolve("compose.override.yaml"), """
                services:
                  app:
                    volumes:
                      - '%s'
                """.formatted(overrideVolume));
        return new ComposeFileLoader().loadMerged(directory, List.of("compose.yaml", "compose.override.yaml"));
    }
}
