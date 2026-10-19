package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposeProject;
import io.github.gattgeng.jcompose.api.ServiceDefinition;
import io.github.gattgeng.jcompose.loader.ComposeFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeProfileDependencyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsRequiredDependencyOutsideActiveProfiles() throws IOException {
        ComposeProject project = load("""
                services:
                  debug:
                    image: busybox
                    profiles: [debug]
                    depends_on: [test]
                  test:
                    image: busybox
                    profiles: [test]
                """);

        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.dependencyOrderedServices(project,
                        List.of(project.services().get("debug")), List.of("debug")));
    }

    @Test
    void skipsOptionalDependencyOutsideActiveProfiles() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    depends_on:
                      helper:
                        condition: service_started
                        required: false
                  helper:
                    image: busybox
                    profiles: [tools]
                """);

        List<ServiceDefinition> ordered = ComposeService.dependencyOrderedServices(project,
                List.of(project.services().get("app")), List.of());

        assertEquals(Set.of("app"), names(ordered));
    }

    @Test
    void explicitProfileServiceEnablesItsProfileForDependencies() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    profiles: [tools]
                    depends_on: [helper]
                  helper:
                    image: busybox
                    profiles: [tools]
                """);
        ServiceDefinition app = project.services().get("app");
        List<String> profiles = ComposeService.effectiveProfiles(List.of(app), List.of());

        List<ServiceDefinition> ordered = ComposeService.dependencyOrderedServices(project, List.of(app), profiles);

        assertEquals(Set.of("app", "helper"), names(ordered));
    }

    @Test
    void rejectsVolumesFromServiceOutsideActiveProfiles() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    profiles: [debug]
                    volumes_from: [helper]
                  helper:
                    image: busybox
                    profiles: [tools]
                """);

        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.dependencyOrderedServices(project,
                        List.of(project.services().get("app")), List.of("debug")));
    }

    @Test
    void networkModeServiceIsSelectedAndStartedBeforeConsumer() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    network_mode: service:network
                  network:
                    image: busybox
                """);

        List<ServiceDefinition> ordered = ComposeService.dependencyOrderedServices(project,
                List.of(project.services().get("app")), List.of());

        assertEquals(List.of("network", "app"), ordered.stream().map(ServiceDefinition::name).toList());
    }

    @Test
    void linksSelectAndStartTheLinkedServiceBeforeConsumer() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    links: [database:db]
                  database:
                    image: busybox
                """);

        List<ServiceDefinition> ordered = ComposeService.dependencyOrderedServices(project,
                List.of(project.services().get("app")), List.of());

        assertEquals(List.of("database", "app"), ordered.stream().map(ServiceDefinition::name).toList());
    }

    @Test
    void rejectsNetworkModeServiceOutsideActiveProfiles() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    network_mode: service:network
                  network:
                    image: busybox
                    profiles: [tools]
                """);

        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.dependencyOrderedServices(project,
                        List.of(project.services().get("app")), List.of()));
    }

    @Test
    void rejectsUnknownNetworkModeService() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    network_mode: service:missing
                """);

        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.dependencyOrderedServices(project,
                        List.of(project.services().get("app")), List.of()));
    }

    @Test
    void ipcAndPidServiceReferencesAreOrderedBeforeConsumer() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    ipc: service:ipc-owner
                    pid: service:pid-owner
                  ipc-owner:
                    image: busybox
                  pid-owner:
                    image: busybox
                """);

        List<ServiceDefinition> ordered = ComposeService.dependencyOrderedServices(project,
                List.of(project.services().get("app")), List.of());

        List<String> orderedNames = ordered.stream().map(ServiceDefinition::name).toList();
        assertEquals(Set.of("ipc-owner", "pid-owner", "app"), Set.copyOf(orderedNames));
        assertTrue(orderedNames.indexOf("ipc-owner") < orderedNames.indexOf("app"));
        assertTrue(orderedNames.indexOf("pid-owner") < orderedNames.indexOf("app"));
    }

    @Test
    void rejectsNamespaceServiceOutsideActiveProfiles() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    ipc: service:ipc-owner
                  ipc-owner:
                    image: busybox
                    profiles: [tools]
                """);

        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.dependencyOrderedServices(project,
                        List.of(project.services().get("app")), List.of()));
    }

    @Test
    void rejectsUnknownNamespaceService() throws IOException {
        ComposeProject project = load("""
                services:
                  app:
                    image: busybox
                    pid: service:missing
                """);

        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.dependencyOrderedServices(project,
                        List.of(project.services().get("app")), List.of()));
    }

    private ComposeProject load(String yaml) throws IOException {
        Files.writeString(temporaryDirectory.resolve("compose.yaml"), yaml);
        return new ComposeFileLoader().loadProject(temporaryDirectory, "compose.yaml");
    }

    private static Set<String> names(List<ServiceDefinition> services) {
        return services.stream().map(ServiceDefinition::name).collect(Collectors.toSet());
    }
}
