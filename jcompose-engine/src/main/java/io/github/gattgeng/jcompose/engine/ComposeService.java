package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.async.ResultCallback.Adapter;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.AuthConfigurations;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.PushResponseItem;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.StreamType;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import io.github.gattgeng.jcompose.api.*;
import io.github.gattgeng.jcompose.loader.ComposeFileLoader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Flow;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Docker Engine backed implementation of the project-scoped lifecycle operations.
 * Container discovery always includes the Compose project label before applying service filters.
 */
public final class ComposeService implements IComposeService {
    private static final Logger LOGGER = Logger.getLogger(ComposeService.class.getName());
    private static final int DEFAULT_STOP_TIMEOUT_SECONDS = 10;
    private static final ExecutorService OPERATION_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ThreadLocal<OperationFuture<?>> CURRENT_OPERATION = new ThreadLocal<>();
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");
    private static final Pattern CONTAINER_HEALTH = Pattern.compile("\\((?:health:\\s*)?(healthy|unhealthy|starting)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTAINER_EXIT_CODE = Pattern.compile("\\bExited\\s+\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);
    private final ComposeFileLoader loader;

    public ComposeService() {
        this(new ComposeFileLoader());
    }

    public ComposeService(ComposeFileLoader loader) {
        this.loader = java.util.Objects.requireNonNull(loader, "loader");
    }

    @Override
    public CompletableFuture<ComposeProjectConfig> loadProject(ComposeProjectContext context) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> services = selectConfigurationServices(project, context.profiles(), null);
            return projectConfig(project, services, context.projectName(), context.composeFileName(), null);
        });
    }

    @Override
    public CompletableFuture<Void> startAsync(ComposeProjectContext context, ComposeStartOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            ComposeProject project = null;
            if (loader.hasComposeFile(context.workingDirectory(), context.composeFileName())) {
                project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            } else if (context.profiles() != null && !context.profiles().isEmpty()) {
                throw new IllegalArgumentException("Cannot select profiles because no Compose file exists under "
                        + context.workingDirectory());
            }

            List<ServiceDefinition> requested = project == null ? List.of()
                    : selectConfigurationServices(project, context.profiles(), options == null ? null : options.services());
            List<String> effectiveProfiles = effectiveProfiles(requested, context.profiles());
            List<ServiceDefinition> ordered = project == null ? List.of()
                    : dependencyOrderedServices(project, requested, effectiveProfiles);
            try (DockerClient client = createDockerClient(context)) {
                if (project == null) {
                    startExistingContainers(client, context.projectName(), options == null ? null : options.services());
                    return null;
                }
                Integer dependencyTimeout = options == null ? null : options.dependencyWaitTimeoutSeconds();
                for (ServiceDefinition service : ordered) {
                    awaitDependencies(client, project, context.projectName(), service, dependencyTimeout, effectiveProfiles);
                    startExistingContainers(client, context.projectName(), List.of(service.name()));
                    if (options != null && options.waitForCompletion()) {
                        awaitServiceRunning(client, context.projectName(), service, options.waitTimeoutSeconds());
                    }
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
            return null;
        });
    }

    private static void startExistingContainers(DockerClient client, String projectName, List<String> selectedServices) {
        for (Container container : projectContainers(client, projectName, selectedServices, true)) {
            if ("true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL))) continue;
            if (!isRunning(container)) {
                try {
                    client.startContainerCmd(container.getId()).exec();
                } catch (DockerException exception) {
                    if (!isAlreadyStartedResponse(exception)) throw exception;
                }
            }
        }
    }

    @Override
    public CompletableFuture<Void> stopAsync(ComposeProjectContext context, ComposeStopOptions options) {
        Integer timeout = options == null ? null : options.timeoutSeconds();
        return execute(context, client -> {
            List<String> selectedServices = selectExistingServices(context, options == null ? null : options.services()).serviceNames();
            for (Container container : projectContainers(client, context.projectName(), selectedServices)) {
                try {
                    var stop = client.stopContainerCmd(container.getId());
                    if (timeout != null) stop.withTimeout(timeout);
                    stop.exec();
                } catch (DockerException ignored) {
                    continue;
                }
                waitUntilNotRunning(client, container.getId());
            }
        });
    }

    @Override
    public CompletableFuture<Void> restartAsync(ComposeProjectContext context, ComposeRestartOptions options) {
        Integer timeout = options == null ? null : options.timeoutSeconds();
        return execute(context, client -> {
            List<String> requestedServices = options == null ? null : options.services();
            List<String> selectedServices = selectExistingServices(context, requestedServices).serviceNames();
            if ((options == null || !options.noDeps()) && loader.hasComposeFile(context.workingDirectory(), context.composeFileName())) {
                ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
                java.util.LinkedHashSet<String> restartServices = new java.util.LinkedHashSet<>(selectedServices);
                Set<String> enabledProfiles = Set.copyOf(effectiveProfiles(selectedServices.stream()
                        .map(project.services()::get).filter(java.util.Objects::nonNull).toList(), context.profiles()));
                boolean changed;
                do {
                    changed = false;
                    for (ServiceDefinition service : project.services().values()) {
                        if (restartServices.contains(service.name())) continue;
                        if (!service.profiles().isEmpty()
                                && service.profiles().stream().noneMatch(enabledProfiles::contains)) continue;
                        if (restartDependencyNames(service).stream().anyMatch(restartServices::contains))
                            changed |= restartServices.add(service.name());
                    }
                } while (changed);
                selectedServices = restartOrder(project, restartServices);
            }
            if (selectedServices == null) {
                restartContainers(client, projectContainers(client, context.projectName(), null), timeout);
                return;
            }
            for (String service : selectedServices) {
                restartContainers(client, projectContainers(client, context.projectName(), List.of(service)), timeout);
            }
        });
    }

    private static void restartContainers(DockerClient client, List<Container> containers, Integer timeout) {
        for (Container container : containers) {
            var restart = client.restartContainerCmd(container.getId());
            if (timeout != null) restart.withTimeout(timeout);
            restart.exec();
        }
    }

    private static List<String> restartDependencyNames(ServiceDefinition service) {
        Object dependencies = service.configuration().get("depends_on");
        if (!(dependencies instanceof Map<?, ?> mapping)) return List.of();
        return mapping.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Map<?, ?> options
                        && Boolean.TRUE.equals(options.get("restart")))
                .map(Map.Entry::getKey).filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static List<String> serviceContainerIds(DockerClient client, String projectName, String serviceName) {
        return projectContainers(client, projectName, List.of(serviceName), true).stream()
                .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                .map(Container::getId).sorted().toList();
    }

    private static List<String> restartOrder(ComposeProject project, Set<String> selectedServices) {
        java.util.LinkedHashSet<String> remaining = new java.util.LinkedHashSet<>(selectedServices);
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        List<String> result = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            List<ServiceDefinition> ready = project.services().values().stream()
                    .filter(service -> remaining.contains(service.name()))
                    .filter(service -> dependencyNames(service.configuration().get("depends_on")).stream()
                            .filter(selectedServices::contains).allMatch(ordered::contains))
                    .toList();
            if (ready.isEmpty()) throw new IllegalArgumentException("Circular service dependency among: "
                    + String.join(", ", remaining) + ".");
            for (ServiceDefinition service : ready) {
                result.add(service.name());
                ordered.add(service.name());
                remaining.remove(service.name());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public CompletableFuture<Void> removeAsync(ComposeProjectContext context, ComposeRemoveOptions options) {
        return execute(context, client -> {
            List<String> selectedServices = selectExistingServices(context, options == null ? null : options.services()).serviceNames();
            List<Container> containers = projectContainers(client, context.projectName(), selectedServices);
            if (options != null && options.stop()) {
                for (Container container : containers) {
                    boolean stopAccepted = true;
                    try {
                        client.stopContainerCmd(container.getId()).exec();
                    } catch (DockerException ignored) {
                        stopAccepted = false;
                        // Continue removing the remaining project containers.
                    }
                    if (stopAccepted) waitUntilNotRunning(client, container.getId());
                }
            }
            for (Container container : containers) {
                try {
                    client.removeContainerCmd(container.getId())
                            .withForce(options != null && options.force())
                            .withRemoveVolumes(options != null && options.volumes())
                            .exec();
                } catch (DockerException ignored) {
                    // ComposeSharp continues the batch when Docker rejects one removal.
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> downAsync(ComposeProjectContext context, ComposeDownOptions options) {
        Integer timeout = options == null ? null : options.timeoutSeconds();
        return execute(context, client -> {
            String removeImages = options == null ? null : options.removeImages();
            if (removeImages != null && !removeImages.equals("all") && !removeImages.equals("local"))
                throw new IllegalArgumentException("Invalid image removal policy '" + removeImages + "'; expected 'local' or 'all'.");
            ExistingServiceSelection selection = selectExistingServices(context, options == null ? null : options.services());
            ComposeProject project = loader.hasComposeFile(context.workingDirectory(), context.composeFileName())
                    ? loader.loadProject(context.workingDirectory(), context.composeFileName()) : null;
            List<Container> containers = new ArrayList<>(projectContainers(client, context.projectName(), selection.serviceNames()));
            if (options != null && options.removeOrphans() && project != null) {
                java.util.Set<String> containerIds = containers.stream().map(Container::getId).collect(java.util.stream.Collectors.toSet());
                for (Container container : projectContainers(client, context.projectName(), null)) {
                    String service = label(container, ComposeConstants.SERVICE_LABEL);
                    if ((service == null || !project.services().containsKey(service)) && containerIds.add(container.getId()))
                        containers.add(container);
                }
            }
            List<String> imagesToRemove = removeImages == null ? List.of()
                    : downImageReferences(context, selection, removeImages, project, containers, options != null && options.removeOrphans());
            for (Container container : containers) {
                try {
                    var stop = client.stopContainerCmd(container.getId());
                    if (timeout != null) stop.withTimeout(timeout);
                    stop.exec();
                } catch (DockerException ignored) {
                    // Continue teardown when the container is already stopped or unavailable.
                }
                try {
                    client.removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(false).exec();
                } catch (NotFoundException ignored) {
                    // A container removed concurrently is already down.
                }
            }
            if (selection.includesAllDefinedServices()) {
                removeProjectNetworks(client, context.projectName());
                if (options != null && options.removeVolumes()) removeProjectVolumes(client, context.projectName());
            }
            for (String image : imagesToRemove) {
                try { client.removeImageCmd(image).exec(); }
                catch (NotFoundException ignored) { /* The image may have been removed concurrently. */ }
            }
        });
    }

    private List<String> downImageReferences(ComposeProjectContext context, ExistingServiceSelection selection, String policy,
                                            ComposeProject project, List<Container> containers, boolean removeOrphans) {
        java.util.Set<String> images = new java.util.LinkedHashSet<>();
        if (project != null) {
            for (String serviceName : selection.serviceNames()) {
                ServiceDefinition service = project.services().get(serviceName);
                if (service == null) continue;
                String image = service.image();
                boolean hasBuild = service.build() != null;
                if (policy.equals("local") && (!hasBuild || (image != null && !image.isBlank()))) continue;
                if ((image == null || image.isBlank()) && !hasBuild) continue;
                images.add(image == null || image.isBlank() ? context.projectName() + "-" + serviceName : image);
            }
            if (removeOrphans) for (Container container : containers) {
                String service = label(container, ComposeConstants.SERVICE_LABEL);
                if (service != null && project.services().containsKey(service)) continue;
                String image = container.getImage();
                if (image == null || image.isBlank()) continue;
                if (policy.equals("local") && (service == null || !image.equals(context.projectName() + "-" + service))) continue;
                images.add(image);
            }
        } else {
            for (Container container : containers) {
                String service = label(container, ComposeConstants.SERVICE_LABEL);
                String image = container.getImage();
                if (image == null || image.isBlank()) continue;
                if (policy.equals("local") && (service == null || !image.equals(context.projectName() + "-" + service))) continue;
                images.add(image);
            }
        }
        return List.copyOf(images);
    }

    @Override
    public CompletableFuture<Void> logsAsync(ComposeProjectContext context, ComposeLogsOptions options, ILogConsumer consumer) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(consumer, "consumer");
            ComposeLogsOptions effective = options == null ? ComposeLogsOptions.builder().build() : options;
            Integer tail = parseTail(effective.tail());
            Integer since = parseLogTimestamp(effective.since(), Instant.now());
            Integer until = parseLogTimestamp(effective.until(), Instant.now());
            List<String> selectedServices = selectExistingServices(context, effective.services()).serviceNames();
            try (DockerClient client = createDockerClient(context)) {
                List<Container> containers = projectContainers(client, context.projectName(), selectedServices, true)
                        .stream().sorted(containerOrder()).toList();
                List<LogCallback> callbacks = new ArrayList<>();
                for (Container container : containers) {
                    String service = label(container, ComposeConstants.SERVICE_LABEL);
                    if (service == null || service.isBlank()) service = container.getId();
                    LogCallback callback = new LogCallback(service, consumer, effective.noColor());
                    var command = client.logContainerCmd(container.getId())
                            .withFollowStream(effective.follow())
                            .withTimestamps(effective.timestamps())
                            .withStdOut(true)
                            .withStdErr(true);
                    if (tail == null) command.withTailAll(); else command.withTail(tail);
                    if (since != null) command.withSince(since);
                    if (until != null) command.withUntil(until);
                    command.exec(callback);
                    callbacks.add(callback);
                }
                for (LogCallback callback : callbacks) await(callback);
                return null;
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> pauseAsync(ComposeProjectContext context, ComposePauseOptions options) {
        return execute(context, client -> {
            List<String> selectedServices = selectExistingServices(context, options == null ? null : options.services()).serviceNames();
            for (Container container : projectContainers(client, context.projectName(), selectedServices)) {
                try { client.pauseContainerCmd(container.getId()).exec(); }
                catch (DockerException ignored) { /* Continue with the remaining containers. */ }
            }
        });
    }

    @Override
    public CompletableFuture<Void> unpauseAsync(ComposeProjectContext context, ComposePauseOptions options) {
        return execute(context, client -> {
            List<String> selectedServices = selectExistingServices(context, options == null ? null : options.services()).serviceNames();
            for (Container container : projectContainers(client, context.projectName(), selectedServices)) {
                try { client.unpauseContainerCmd(container.getId()).exec(); }
                catch (DockerException ignored) { /* Continue with the remaining containers. */ }
            }
        });
    }

    @Override
    public CompletableFuture<Void> killAsync(ComposeProjectContext context, ComposeKillOptions options) {
        String signal = options == null || options.signal() == null || options.signal().isBlank() ? "SIGKILL" : options.signal();
        return execute(context, client -> {
            List<String> selectedServices = selectExistingServices(context, options == null ? null : options.services()).serviceNames();
            for (Container container : projectContainers(client, context.projectName(), selectedServices)) {
                try { client.killContainerCmd(container.getId()).withSignal(signal).exec(); }
                catch (DockerException ignored) { /* Continue with the remaining containers. */ }
            }
        });
    }

    @Override
    public CompletableFuture<List<ContainerSummary>> psAsync(ComposeProjectContext context, ComposePsOptions options) {
        boolean showAll = options == null || options.all();
        return supply(() -> {
            List<String> selectedServices = selectExistingServices(context, options == null ? null : options.services()).serviceNames();
            List<String> statuses = new ArrayList<>();
            if (options != null && options.status() != null && !options.status().isBlank()) statuses.add(options.status());
            if (options != null && options.filter() != null && !options.filter().isBlank()) {
                String filter = options.filter().trim();
                if (!filter.startsWith("status=")) throw new IllegalArgumentException("Compose ps only supports the status filter.");
                statuses.add(filter.substring("status=".length()).trim());
            }
            try (DockerClient client = createDockerClient(context)) {
                List<Container> containers = projectContainers(client, context.projectName(), selectedServices, showAll, true);
                return containers.stream()
                        .filter(container -> statuses.isEmpty() || statuses.stream().anyMatch(status -> status.equalsIgnoreCase(container.getState())))
                        .map(container -> options != null && options.quiet()
                                ? containerIdResult(container.getId()) : containerResult(container, context.projectName())).toList();
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<Stack>> listAsync(ComposeListOptions options) {
        return supply(() -> {
            try (DockerClient client = createDockerClient(null)) {
                List<Container> allContainers = projectContainers(client, null, null, true);
                java.util.Set<String> visibleProjects = null;
                if (options == null || !options.all()) {
                    visibleProjects = projectContainers(client, null, null, false).stream()
                            .map(container -> label(container, ComposeConstants.PROJECT_LABEL))
                            .filter(java.util.Objects::nonNull)
                            .collect(java.util.stream.Collectors.toSet());
                }
                Map<String, List<Container>> byProject = new java.util.TreeMap<>();
                for (Container container : allContainers) {
                    String project = label(container, ComposeConstants.PROJECT_LABEL);
                    if (project != null && (visibleProjects == null || visibleProjects.contains(project))) {
                        byProject.computeIfAbsent(project, ignored -> new ArrayList<>()).add(container);
                    }
                }
                return byProject.entrySet().stream().map(entry -> {
                    List<String> states = entry.getValue().stream().map(Container::getState).toList();
                    String status = stackStatus(states);
                    return new Stack(entry.getKey(), entry.getKey(), status, "", null);
                }).toList();
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    @Override
    public CompletableFuture<List<ContainerProcSummary>> topAsync(ComposeProjectContext context, ComposeTopOptions options) {
        if (options != null && options.services() != null && options.services().isEmpty())
            return CompletableFuture.completedFuture(List.of());
        return supply(() -> {
            List<String> selectedServices = selectExistingServices(context, options == null ? null : options.services()).serviceNames();
            try (DockerClient client = createDockerClient(context)) {
                return projectContainers(client, context.projectName(), selectedServices, true).stream()
                        .filter(ComposeService::isRunning)
                        .sorted(containerOrder())
                        .map(container -> {
                            com.github.dockerjava.api.command.TopContainerResponse response;
                            try {
                                response = client.topContainerCmd(container.getId()).exec();
                            } catch (DockerException exception) {
                                if (isTopListingRace(exception)) return null;
                                throw exception;
                            }
                            List<String> titles = response.getTitles() == null
                                    ? List.of() : List.of(response.getTitles());
                            List<List<String>> processes = response.getProcesses() == null ? List.of()
                                    : java.util.Arrays.stream(response.getProcesses())
                                    .map(row -> List.of(row)).toList();
                            List<String> names = container.getNames() == null ? List.of()
                                    : java.util.Arrays.stream(container.getNames()).map(name -> name.startsWith("/") ? name.substring(1) : name).toList();
                            String service = label(container, ComposeConstants.SERVICE_LABEL);
                            return new ContainerProcSummary(container.getId(), names.isEmpty() ? container.getId() : names.getFirst(),
                                    service == null ? "" : service, label(container, ComposeConstants.CONTAINER_NUMBER_LABEL),
                                    titles, processes);
                        }).filter(java.util.Objects::nonNull).toList();
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    static boolean isTopListingRace(DockerException exception) {
        return exception.getHttpStatus() == 404 || exception.getHttpStatus() == 409;
    }

    static boolean isAlreadyStartedResponse(DockerException exception) {
        return exception.getHttpStatus() == 304;
    }

    @Override
    public CompletableFuture<List<ImageSummary>> imagesAsync(ComposeProjectContext context, ComposeImagesOptions options) {
        return supply(() -> {
            List<String> selectedServices = selectExistingServices(context, options == null ? null : options.services()).serviceNames();
            try (DockerClient client = createDockerClient(context)) {
                java.util.Set<String> usedImageIds = projectContainers(client, context.projectName(), selectedServices, true)
                        .stream().map(Container::getImageId).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                if (usedImageIds.isEmpty()) return List.of();
                List<ImageSummary> summaries = client.listImagesCmd().exec().stream()
                        .filter(image -> usedImageIds.contains(image.getId()))
                        .sorted(java.util.Comparator.comparing(Image::getId))
                        .map(ComposeService::imageSummary)
                        .toList();
                if (options == null || !options.quiet()) return summaries;
                return summaries.stream()
                        .map(image -> new ImageSummary(image.id(), null, null, null, 0L, null, null))
                        .toList();
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    @Override
    public CompletableFuture<PortResult> portAsync(
            ComposeProjectContext context, String service, int port, ComposePortOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            if (service == null || service.isBlank()) throw new IllegalArgumentException("service is required");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be between 1 and 65535");
            String protocolName = options == null || options.protocol() == null || options.protocol().isBlank()
                    ? "tcp" : options.protocol().toLowerCase(java.util.Locale.ROOT);
            InternetProtocol protocol = switch (protocolName) {
                case "tcp" -> InternetProtocol.TCP;
                case "udp" -> InternetProtocol.UDP;
                default -> throw new IllegalArgumentException("Unsupported port protocol: " + protocolName);
            };
            try (DockerClient client = createDockerClient(context)) {
                List<Container> running = projectContainers(client, context.projectName(), List.of(service), true).stream()
                        .filter(ComposeService::isRunning)
                        .sorted(containerOrder())
                        .toList();
                Integer requestedIndex = options == null ? null : options.index();
                if (requestedIndex != null && requestedIndex < 1)
                    throw new IllegalArgumentException("replica index must be 1 or greater");
                if (running.isEmpty() || requestedIndex != null && requestedIndex > running.size())
                    throw new IllegalStateException("No running container found for service '" + service + "'.");
                Container container = running.get(requestedIndex == null ? 0 : requestedIndex - 1);
                var inspected = client.inspectContainerCmd(container.getId()).exec();
                ExposedPort exposedPort = new ExposedPort(port, protocol);
                var networkSettings = inspected.getNetworkSettings();
                Ports ports = networkSettings == null ? null : networkSettings.getPorts();
                Ports.Binding[] bindings = ports == null ? null : ports.getBindings().get(exposedPort);
                if (bindings == null || bindings.length == 0)
                    throw new IllegalStateException("Port " + port + "/" + protocolName
                            + " is not published for service '" + service + "'.");
                Ports.Binding binding = bindings[0];
                String host = binding.getHostIp();
                String hostPort = binding.getHostPortSpec();
                if (hostPort == null || hostPort.isBlank())
                    throw new IllegalStateException("Docker returned an empty host port for service '" + service + "'.");
                return new PortResult(host == null || host.isBlank() ? "0.0.0.0" : host, Integer.parseInt(hostPort));
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    @Override
    public CompletableFuture<ComposeProjectConfig> generateAsync(ComposeProjectContext context, ComposeGenerateOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            if (options != null && options.containers() != null && !options.containers().isEmpty()) {
                throw new UnsupportedOperationException("generate cannot inspect container names or IDs; select services instead.");
            }
            String projectName = options == null || options.projectName() == null
                    ? context.projectName() : options.projectName();
            if (projectName.isBlank()) throw new IllegalArgumentException("projectName must not be blank");

            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> services = selectConfigurationServices(project, context.profiles(),
                    options == null ? null : options.services());
            String yaml = ComposeConfigRenderer.render(project, services, projectName);
            return projectConfig(project, services, projectName, context.composeFileName(), yaml);
        });
    }

    private static ComposeProjectConfig projectConfig(ComposeProject project, List<ServiceDefinition> services,
                                                       String projectName,
                                                       String composeFileName,
                                                       String renderedYaml) {
        return new ComposeProjectConfig(projectName, project.sourceFile().getParent().toString(),
                projectConfigFiles(project, composeFileName), services.stream().map(ServiceDefinition::name).toList(),
                project.networks(), project.volumes(), project.secrets(), project.configs(), project.environment(), renderedYaml);
    }

    private static List<String> projectConfigFiles(ComposeProject project, String composeFileName) {
        List<String> files = new ArrayList<>(List.of(composeFileName));
        Path requested = Path.of(composeFileName);
        if (requested.isAbsolute() || requested.getNameCount() != 1) return List.copyOf(files);
        String baseName = project.sourceFile().getFileName().toString();
        String overrideName = switch (baseName) {
            case "compose.yaml" -> "compose.override.yaml";
            case "compose.yml" -> "compose.override.yml";
            case "docker-compose.yaml" -> "docker-compose.override.yaml";
            case "docker-compose.yml" -> "docker-compose.override.yml";
            default -> null;
        };
        if (overrideName != null && Files.isRegularFile(project.sourceFile().resolveSibling(overrideName)))
            files.add(overrideName);
        return List.copyOf(files);
    }

    @Override
    public CompletableFuture<String> vizAsync(ComposeProjectContext context) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> services = selectConfigurationServices(project, context.profiles(), null);
            StringBuilder graph = new StringBuilder("digraph ").append(quote(context.projectName())).append(" {\n  rankdir=LR;\n");
            for (ServiceDefinition service : services) {
                String image = service.image() == null ? "build" : service.image();
                graph.append("  ").append(quote(service.name())).append(" [label=")
                        .append(quote(service.name() + "\n" + image)).append("];\n");
                for (String dependency : dependencyNames(service.configuration().get("depends_on"))) {
                    graph.append("  ").append(quote(service.name())).append(" -> ")
                            .append(quote(dependency)).append(";\n");
                }
            }
            return graph.append("}\n").toString();
        });
    }

    @Override
    public CompletableFuture<List<VolumesSummary>> volumesAsync(ComposeProjectContext context, ComposeVolumesOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            try (DockerClient client = createDockerClient(context)) {
                var response = client.listVolumesCmd().exec();
                if (response == null || response.getVolumes() == null) return List.of();
                List<String> requestedServices = options == null ? null : services(options.services());
                if (requestedServices != null && requestedServices.isEmpty()) return List.of();
                java.util.Set<String> usedVolumeNames = null;
                if (requestedServices != null) {
                    List<String> selectedServices = selectExistingServices(context, requestedServices).serviceNames();
                    usedVolumeNames = new java.util.HashSet<>();
                    for (Container container : projectContainers(client, context.projectName(), selectedServices, true)) {
                        if (container.getMounts() == null) continue;
                        for (var mount : container.getMounts()) {
                            if (mount.getName() != null) usedVolumeNames.add(mount.getName());
                        }
                    }
                }
                java.util.Set<String> selectedVolumeNames = usedVolumeNames;
                return response.getVolumes().stream()
                        .filter(volume -> volume.getLabels() != null
                                && context.projectName().equals(volume.getLabels().get(ComposeConstants.PROJECT_LABEL))
                                && (selectedVolumeNames == null || selectedVolumeNames.contains(volume.getName())))
                        .map(volume -> {
                            return new VolumesSummary(volume.getName(), volume.getDriver(), volume.getMountpoint(),
                                    volume.getLabels(), null, volume.getOptions(), null);
                        }).toList();
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> pullAsync(ComposeProjectContext context, ComposePullOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            if (context.offline()) throw new IllegalStateException("Cannot pull images when the Compose project context is offline.");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> selected = selectConfigurationServices(project, context.profiles(),
                    options == null ? null : options.services());
            if (options != null && options.includeDeps())
                selected = dependencyClosure(project, selected, effectiveProfiles(selected, context.profiles()));
            try (DockerClient client = createDockerClient(context)) {
                java.util.Set<String> pulledImages = new java.util.HashSet<>();
                for (ServiceDefinition service : selected) {
                    String image = service.image();
                    if (image == null || image.isBlank()) continue;
                    if (!shouldPullForExplicitPull(client, image, service)) continue;
                    if (pulledImages.contains(image)) continue;
                    try {
                        String platform = options == null ? null : options.platform();
                        if (platform == null || platform.isBlank())
                            platform = stringValue(service.configuration().get("platform"), null);
                        pullImage(client, context.registryAuth(), image, platform);
                        pulledImages.add(image);
                    } catch (RuntimeException failure) {
                        if (failure instanceof java.util.concurrent.CancellationException) throw failure;
                        if (options == null || !options.ignoreFailures()) throw failure;
                    }
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> pushAsync(ComposeProjectContext context, ComposePushOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            if (context.offline()) throw new IllegalStateException("Cannot push images when the Compose project context is offline.");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> selected = selectConfigurationServices(project, context.profiles(),
                    options == null ? null : options.services());
            try (DockerClient client = createDockerClient(context)) {
                for (ServiceDefinition service : selected) {
                    String image = service.image();
                    if (image == null || image.isBlank()) continue;
                    try {
                        pushImage(client, context.registryAuth(), image);
                    } catch (RuntimeException failure) {
                        if (failure instanceof java.util.concurrent.CancellationException) throw failure;
                        if (options == null || !options.ignoreFailures()) throw failure;
                    }
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
            return null;
        });
    }

    private static List<ServiceDefinition> dependencyClosure(ComposeProject project, List<ServiceDefinition> selected,
                                                            List<String> activeProfiles) {
        Map<String, ServiceDefinition> servicesByName = project.services();
        Set<String> enabledProfiles = activeProfiles == null ? Set.of() : Set.copyOf(activeProfiles);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        selected.forEach(service -> pending.add(service.name()));
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!names.add(name)) continue;
            ServiceDefinition service = servicesByName.get(name);
            for (String dependency : dependencyNames(service.configuration().get("depends_on"))) {
                if (!servicesByName.containsKey(dependency)) {
                    throw new IllegalArgumentException("Service '" + name + "' depends on unknown service '" + dependency + "'.");
                }
                ServiceDefinition definition = servicesByName.get(dependency);
                if (!definition.profiles().isEmpty()
                        && definition.profiles().stream().noneMatch(enabledProfiles::contains)) {
                    if (dependencyRequired(service, dependency))
                        throw new IllegalArgumentException("Service '" + name + "' depends on service '"
                                + dependency + "', but none of its profiles are active.");
                    continue;
                }
                pending.addLast(dependency);
            }
        }
        return names.stream().map(servicesByName::get).toList();
    }

    private static void pullImage(DockerClient client, DockerRegistryAuth registryAuth, String image, String platform) {
        String[] reference = splitImageReference(image);
        var command = client.pullImageCmd(reference[0]).withTag(reference[1]).withAuthConfig(registryAuth(registryAuth));
        if (platform != null && !platform.isBlank()) command.withPlatform(platform);
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
        ResultCallback.Adapter<PullResponseItem> callback = command.exec(new ResultCallback.Adapter<>() {
            @Override public void onNext(PullResponseItem item) {
                if (item.getError() != null && !item.getError().isBlank()) failure.compareAndSet(null, item.getError());
            }
        });
        await(callback);
        String error = failure.get();
        if (error != null) throw new IllegalStateException("Pull of image '" + image + "' failed: " + error);
    }

    private static void pushImage(DockerClient client, DockerRegistryAuth registryAuth, String image) {
        String[] reference = splitImageReference(image);
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
        ResultCallback.Adapter<PushResponseItem> callback = client.pushImageCmd(reference[0]).withTag(reference[1])
                .withAuthConfig(registryAuth(registryAuth)).exec(new ResultCallback.Adapter<>() {
                    @Override public void onNext(PushResponseItem item) {
                        if (item.getError() != null && !item.getError().isBlank()) failure.compareAndSet(null, item.getError());
                    }
                });
        await(callback);
        String error = failure.get();
        if (error != null) throw new IllegalStateException("Push of image '" + image + "' failed: " + error);
    }

    private static String[] splitImageReference(String image) {
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        return colon > slash ? new String[]{image.substring(0, colon), image.substring(colon + 1)} : new String[]{image, "latest"};
    }

    private static AuthConfig registryAuth(DockerRegistryAuth auth) {
        if (auth == null) return null;
        return new AuthConfig().withUsername(auth.username()).withPassword(auth.password()).withEmail(auth.email())
                .withRegistryAddress(auth.serverAddress() == null || auth.serverAddress().isBlank()
                        ? "https://index.docker.io/v1/" : auth.serverAddress());
    }

    private static void await(ResultCallback.Adapter<?> callback) {
        try {
            callback.awaitCompletion();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Docker image operation was interrupted.");
        }
    }

    private static List<String> dependencyNames(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        if (value instanceof Map<?, ?> map) return map.keySet().stream().map(String::valueOf).toList();
        return List.of();
    }

    private record VolumeFromReference(String source, boolean externalContainer,
                                       com.github.dockerjava.api.model.AccessMode accessMode) {}

    private static List<VolumeFromReference> volumeFromReferences(Object value) {
        List<VolumeFromReference> references = new ArrayList<>();
        for (String entry : asStringList(value)) {
            String[] parts = entry.split(":", -1);
            boolean externalContainer = "container".equals(parts[0]);
            int sourceIndex = externalContainer ? 1 : 0;
            int expectedParts = externalContainer ? 2 : 1;
            if (parts.length < expectedParts || parts.length > expectedParts + 1
                    || parts[sourceIndex].isBlank()) {
                throw new IllegalArgumentException("Invalid volumes_from entry: " + entry);
            }
            com.github.dockerjava.api.model.AccessMode accessMode = com.github.dockerjava.api.model.AccessMode.DEFAULT;
            if (parts.length == expectedParts + 1) {
                try {
                    accessMode = com.github.dockerjava.api.model.AccessMode.valueOf(parts[expectedParts]);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Invalid volumes_from access mode in entry: " + entry, exception);
                }
            }
            references.add(new VolumeFromReference(parts[sourceIndex], externalContainer, accessMode));
        }
        return List.copyOf(references);
    }

    private static List<String> serviceDependencyNames(ServiceDefinition service) {
        java.util.LinkedHashSet<String> dependencies = new java.util.LinkedHashSet<>(
                dependencyNames(service.configuration().get("depends_on")));
        volumeFromReferences(service.configuration().get("volumes_from")).stream()
                .filter(reference -> !reference.externalContainer())
                .map(VolumeFromReference::source)
                .forEach(dependencies::add);
        return List.copyOf(dependencies);
    }

    private static java.util.Comparator<Container> containerOrder() {
        return java.util.Comparator
                .comparing((Container container) -> label(container, ComposeConstants.SERVICE_LABEL),
                        java.util.Comparator.nullsLast(String::compareTo))
                .thenComparingInt(ComposeService::replicaNumber)
                .thenComparing(Container::getId);
    }

    private static int replicaNumber(Container container) {
        String value = label(container, ComposeConstants.CONTAINER_NUMBER_LABEL);
        if (value == null) return Integer.MAX_VALUE;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }

    private static ImageSummary imageSummary(Image image) {
        String reference = image.getRepoTags() == null || image.getRepoTags().length == 0
                ? "<none>:<none>" : image.getRepoTags()[0];
        int slash = reference.lastIndexOf('/');
        int colon = reference.lastIndexOf(':');
        String repository = colon > slash ? reference.substring(0, colon) : reference;
        String tag = colon > slash ? reference.substring(colon + 1) : "latest";
        Instant created = image.getCreated() == null || image.getCreated() <= 0 ? null : Instant.ofEpochSecond(image.getCreated());
        return new ImageSummary(image.getId(), repository, tag, null,
                image.getSize() == null ? 0L : image.getSize(), created, null);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    private static List<ServiceDefinition> selectConfigurationServices(
            ComposeProject project, List<String> profiles, List<String> explicitServices) {
        if (explicitServices != null && !explicitServices.isEmpty()) {
            Map<String, ServiceDefinition> byName = new LinkedHashMap<>();
            project.services().forEach((name, service) -> byName.put(name, service));
            List<ServiceDefinition> selected = new ArrayList<>();
            for (String name : explicitServices) {
                ServiceDefinition service = byName.get(name);
                if (service == null) throw new IllegalArgumentException("Unknown service '" + name + "' in the loaded project.");
                selected.add(service);
            }
            return List.copyOf(selected);
        }
        return project.services().values().stream()
                .filter(service -> service.profiles().isEmpty()
                        || profiles != null && service.profiles().stream().anyMatch(profiles::contains))
                .toList();
    }

    private static void removeProjectNetworks(DockerClient client, String project) {
        client.listNetworksCmd().withFilter("label", List.of(ComposeConstants.PROJECT_LABEL + "=" + project)).exec()
                .forEach(network -> client.removeNetworkCmd(network.getId()).exec());
    }

    private static void removeProjectVolumes(DockerClient client, String project) {
        var response = client.listVolumesCmd().exec();
        if (response == null || response.getVolumes() == null) return;
        response.getVolumes().stream()
                .filter(volume -> volume.getLabels() != null && project.equals(volume.getLabels().get(ComposeConstants.PROJECT_LABEL)))
                .forEach(volume -> client.removeVolumeCmd(volume.getName()).exec());
    }

    private static List<Container> projectContainers(DockerClient client, String project, List<String> services) {
        return projectContainers(client, project, services, true);
    }

    private static List<Container> projectContainers(DockerClient client, String project, List<String> services, boolean all) {
        return projectContainers(client, project, services, all, false);
    }

    private static List<Container> projectContainers(DockerClient client, String project, List<String> services,
                                                     boolean all, boolean showSize) {
        if (services != null && services.isEmpty()) return List.of();
        ListContainersCmd command = client.listContainersCmd()
                .withShowAll(all)
                .withShowSize(showSize);
        if (project != null) command.withLabelFilter(Map.of(ComposeConstants.PROJECT_LABEL, project));
        return command.exec().stream()
                .filter(container -> services == null || services.contains(label(container, ComposeConstants.SERVICE_LABEL)))
                .filter(container -> project == null || project.equals(label(container, ComposeConstants.PROJECT_LABEL)))
                .toList();
    }

    private static ContainerSummary containerResult(Container container, String project) {
        String service = label(container, ComposeConstants.SERVICE_LABEL);
        List<String> names = container.getNames() == null ? List.of() : java.util.Arrays.stream(container.getNames())
                .map(name -> name.startsWith("/") ? name.substring(1) : name).toList();
        List<PortPublisher> ports = new ArrayList<>();
        if (container.getPorts() != null) for (var port : container.getPorts()) {
            ports.add(new PortPublisher(null, port.getPrivatePort(), port.getPublicPort(),
                    port.getType() == null ? "tcp" : port.getType(), port.getIp()));
        }
        long created = container.getCreated() == null ? 0L : container.getCreated();
        Map<String, String> labels = container.getLabels() == null ? Map.of() : Map.copyOf(container.getLabels());
        List<String> networks = container.getNetworkSettings() == null || container.getNetworkSettings().getNetworks() == null
                ? List.of() : List.copyOf(container.getNetworkSettings().getNetworks().keySet());
        List<MountInfo> mounts = container.getMounts() == null ? List.of() : container.getMounts().stream()
                .map(mount -> new MountInfo(mount.getName() == null ? null : "volume", mount.getSource(),
                        mount.getDestination(), mount.getMode(), mount.getRw())).toList();
        String name = names.isEmpty() ? container.getId().substring(0, Math.min(12, container.getId().length())) : names.getFirst();
        int exitCode = containerExitCode(container.getStatus());
        return new ContainerSummary(container.getId(), name, names, container.getImage(), container.getCommand(),
                project == null ? "" : project, service == null ? "" : service, created, container.getState(),
                container.getStatus(), containerHealth(container.getStatus()), exitCode, ports, labels, networks,
                mounts, (int) mounts.stream().filter(mount -> "volume".equals(mount.type())).count(),
                container.getSizeRw() == null ? 0 : container.getSizeRw(),
                container.getSizeRootFs() == null ? 0 : container.getSizeRootFs());
    }

    static String containerHealth(String status) {
        if (status == null) return null;
        var matcher = CONTAINER_HEALTH.matcher(status);
        return matcher.find() ? matcher.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    static int containerExitCode(String status) {
        if (status == null) return 0;
        var matcher = CONTAINER_EXIT_CODE.matcher(status);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static ContainerSummary containerIdResult(String id) {
        return new ContainerSummary(id, null, List.of(), null, null, null, null, 0, null, null, null,
                0, List.of(), Map.of(), List.of(), List.of(), 0, 0, 0);
    }

    static String stackStatus(List<String> states) {
        if (states.isEmpty() || states.stream().anyMatch(state ->
                !"running".equalsIgnoreCase(state) && !"exited".equalsIgnoreCase(state)
                        && !"dead".equalsIgnoreCase(state))) return "unknown";
        boolean running = states.stream().anyMatch(state -> "running".equalsIgnoreCase(state));
        boolean exited = states.stream().allMatch(state -> "exited".equalsIgnoreCase(state) || "dead".equalsIgnoreCase(state));
        if (running && states.stream().allMatch(state -> "running".equalsIgnoreCase(state))) return "running";
        if (exited) return "exited";
        if (running && states.stream().anyMatch(state -> !"running".equalsIgnoreCase(state))) return "partial";
        return "unknown";
    }

    private static String label(Container container, String name) {
        Map<String, String> labels = container.getLabels();
        return labels == null ? null : labels.get(name);
    }

    private static boolean isRunning(Container container) {
        return "running".equalsIgnoreCase(container.getState());
    }

    private static List<String> services(List<String> names) { return names; }

    private ExistingServiceSelection selectExistingServices(ComposeProjectContext context, List<String> explicitServices) {
        List<String> requestedServices = explicitServices == null || explicitServices.isEmpty() ? null : explicitServices;
        if (loader.hasComposeFile(context.workingDirectory(), context.composeFileName())) {
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> selected = selectConfigurationServices(project, context.profiles(), requestedServices);
            boolean includesAllDefinedServices = requestedServices == null && selected.size() == project.services().size();
            return new ExistingServiceSelection(selected.stream().map(ServiceDefinition::name).toList(), includesAllDefinedServices);
        }
        if (context.profiles() != null && !context.profiles().isEmpty())
            throw new IllegalArgumentException("Cannot select profiles because no Compose file exists under " + context.workingDirectory());
        return new ExistingServiceSelection(requestedServices, requestedServices == null);
    }

    private record ExistingServiceSelection(List<String> serviceNames, boolean includesAllDefinedServices) { }

    static List<String> effectiveProfiles(List<ServiceDefinition> selected, List<String> activeProfiles) {
        java.util.LinkedHashSet<String> profiles = new java.util.LinkedHashSet<>();
        if (activeProfiles != null) profiles.addAll(activeProfiles);
        selected.forEach(service -> profiles.addAll(service.profiles()));
        return List.copyOf(profiles);
    }

    static List<ServiceDefinition> dependencyOrderedServices(ComposeProject project, List<ServiceDefinition> selected,
                                                              List<String> activeProfiles) {
        Map<String, ServiceDefinition> byName = project.services();
        Set<String> enabledProfiles = activeProfiles == null ? Set.of() : Set.copyOf(activeProfiles);
        java.util.Set<String> selectedNames = new java.util.LinkedHashSet<>();
        selected.forEach(service -> selectedNames.add(service.name()));
        List<ServiceDefinition> pending = new ArrayList<>(selected);

        // Include only the transitive dependency closure of the selected services.
        for (int index = 0; index < pending.size(); index++) {
            ServiceDefinition service = pending.get(index);
            for (String name : dependencyNames(service.configuration().get("depends_on"))) {
                ServiceDefinition dependency = byName.get(name);
                if (dependency == null) throw new IllegalArgumentException("Service '" + service.name()
                        + "' depends on unknown service '" + name + "'.");
                if (!dependency.profiles().isEmpty()
                        && dependency.profiles().stream().noneMatch(enabledProfiles::contains)) {
                    if (dependencyRequired(service, name))
                        throw new IllegalArgumentException("Service '" + service.name() + "' depends on service '"
                                + name + "', but none of its profiles are active.");
                    continue;
                }
                if (selectedNames.add(name)) pending.add(dependency);
            }
            String networkService = networkModeService(service);
            if (networkService != null) {
                ServiceDefinition dependency = byName.get(networkService);
                if (dependency == null) throw new IllegalArgumentException("Service '" + service.name()
                        + "' uses network_mode service '" + networkService + "', but that service is not defined.");
                if (!dependency.profiles().isEmpty()
                        && dependency.profiles().stream().noneMatch(enabledProfiles::contains))
                    throw new IllegalArgumentException("Service '" + service.name() + "' uses network_mode service '"
                            + networkService + "', but none of its profiles are active.");
                if (selectedNames.add(networkService)) pending.add(dependency);
            }
            for (VolumeFromReference reference : volumeFromReferences(service.configuration().get("volumes_from"))) {
                if (reference.externalContainer()) continue;
                String name = reference.source();
                ServiceDefinition dependency = byName.get(name);
                if (dependency == null) throw new IllegalArgumentException("Service '" + service.name()
                        + "' depends on unknown service '" + name + "'.");
                if (!dependency.profiles().isEmpty()
                        && dependency.profiles().stream().noneMatch(enabledProfiles::contains))
                    throw new IllegalArgumentException("Service '" + service.name() + "' uses volumes_from service '"
                            + name + "', but none of its profiles are active.");
                if (selectedNames.add(name)) pending.add(dependency);
            }
        }

        java.util.Set<String> remainingNames = new java.util.LinkedHashSet<>(selectedNames);
        java.util.Set<String> orderedNames = new java.util.LinkedHashSet<>();
        List<ServiceDefinition> ordered = new ArrayList<>(selectedNames.size());
        while (!remainingNames.isEmpty()) {
            List<ServiceDefinition> ready = project.services().values().stream()
                    .filter(service -> remainingNames.contains(service.name()))
                    .filter(service -> selectedDependencyNames(project, service, enabledProfiles).stream()
                            .allMatch(orderedNames::contains))
                    .toList();
            if (ready.isEmpty()) throw new IllegalArgumentException("Circular service dependency among: "
                    + String.join(", ", remainingNames) + ".");
            for (ServiceDefinition service : ready) {
                ordered.add(service);
                orderedNames.add(service.name());
                remainingNames.remove(service.name());
            }
        }
        return List.copyOf(ordered);
    }

    private static List<String> selectedDependencyNames(ComposeProject project, ServiceDefinition service,
                                                        Set<String> enabledProfiles) {
        java.util.LinkedHashSet<String> dependencies = new java.util.LinkedHashSet<>();
        for (String name : dependencyNames(service.configuration().get("depends_on"))) {
            ServiceDefinition dependency = project.services().get(name);
            if (!dependencyRequired(service, name) && dependency != null
                    && !dependency.profiles().isEmpty()
                    && dependency.profiles().stream().noneMatch(enabledProfiles::contains)) continue;
            dependencies.add(name);
        }
        volumeFromReferences(service.configuration().get("volumes_from")).stream()
                .filter(reference -> !reference.externalContainer())
                .map(VolumeFromReference::source)
                .forEach(dependencies::add);
        String networkService = networkModeService(service);
        if (networkService != null) dependencies.add(networkService);
        return List.copyOf(dependencies);
    }

    private static String networkModeService(ServiceDefinition service) {
        String mode = stringValue(service.configuration().get("network_mode"), "").trim();
        if (!mode.startsWith("service:")) return null;
        String name = mode.substring("service:".length()).trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Service '" + service.name()
                + "' has an empty network_mode service reference.");
        return name;
    }

    private static String dockerNetworkMode(DockerClient client, String projectName, ServiceDefinition service) {
        String mode = stringValue(service.configuration().get("network_mode"), "");
        String targetService = networkModeService(service);
        if (targetService == null) return mode;
        Container target = projectContainers(client, projectName, List.of(targetService), true).stream()
                .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                .sorted(containerOrder())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Service '" + service.name()
                        + "' requires network_mode service '" + targetService + "', but its container was not created."));
        return "container:" + target.getId();
    }

    private static boolean dependencyRequired(ServiceDefinition service, String dependency) {
        Object configured = service.configuration().get("depends_on");
        if (configured instanceof Map<?, ?> mapping && mapping.get(dependency) instanceof Map<?, ?> options)
            return !Boolean.FALSE.equals(options.get("required"));
        return true;
    }

    private static void ensureProjectResources(DockerClient client, String projectName, ComposeProject project) {
        java.util.LinkedHashSet<String> declaredNetworks = new java.util.LinkedHashSet<>(project.networks());
        for (ServiceDefinition service : project.services().values()) {
            if (stringValue(service.configuration().get("network_mode"), "").isBlank())
                declaredNetworks.addAll(serviceNetworks(service));
        }
        List<String> networkNames = List.copyOf(declaredNetworks);
        java.util.Set<String> existingNetworks = client.listNetworksCmd().exec().stream()
                .map(com.github.dockerjava.api.model.Network::getName).collect(java.util.stream.Collectors.toSet());
        for (String network : networkNames) {
            Map<String, Object> definition = networkDefinition(project, network);
            String name = engineNetworkName(projectName, project, network);
            boolean external = externalResource(definition);
            if (external) {
                if (!existingNetworks.contains(name)) throw new IllegalStateException("External Docker network '" + name + "' does not exist.");
            } else if (!existingNetworks.contains(name)) {
                var create = client.createNetworkCmd().withName(name)
                        .withDriver(stringValue(definition.get("driver"), "bridge")).withCheckDuplicate(true)
                        .withLabels(resourceLabels(definition, projectName));
                if (definition.get("driver_opts") instanceof Map<?, ?> options) create.withOptions(stringMap(options));
                if (definition.get("internal") instanceof Boolean value) create.withInternal(value);
                if (definition.get("enable_ipv6") instanceof Boolean value) create.withEnableIpv6(value);
                if (definition.get("attachable") instanceof Boolean value) create.withAttachable(value);
                Map<String, Object> ipamOptions = definition.get("ipam") instanceof Map<?, ?> rawIpam ? stringKeyMap(rawIpam) : Map.of();
                if (!ipamOptions.isEmpty()) create.withIpam(networkIpam(ipamOptions));
                create.exec();
            }
        }
        var volumesResponse = client.listVolumesCmd().exec();
        java.util.Set<String> existingVolumes = volumesResponse == null || volumesResponse.getVolumes() == null ? java.util.Set.of()
                : volumesResponse.getVolumes().stream().map(com.github.dockerjava.api.command.InspectVolumeResponse::getName)
                .collect(java.util.stream.Collectors.toSet());
        for (String volume : project.volumes()) {
            Map<String, Object> definition = volumeDefinition(project, volume);
            String name = engineVolumeName(projectName, project, volume);
            if (externalResource(definition)) {
                if (!existingVolumes.contains(name)) throw new IllegalStateException("External Docker volume '" + name + "' does not exist.");
            } else if (!existingVolumes.contains(name)) {
                var create = client.createVolumeCmd().withName(name)
                        .withLabels(resourceLabels(definition, projectName));
                if (definition.get("driver") != null) create.withDriver(String.valueOf(definition.get("driver")));
                if (definition.get("driver_opts") instanceof Map<?, ?> options) create.withDriverOpts(stringMap(options));
                create.exec();
            }
        }
    }

    private static void removeOrphanContainers(DockerClient client, String projectName, ComposeProject project) {
        java.util.Set<String> valid = project.services().keySet();
        for (Container container : projectContainers(client, projectName, null, true)) {
            String service = label(container, ComposeConstants.SERVICE_LABEL);
            if (service != null && !valid.contains(service))
                client.removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(false).exec();
        }
    }

    private static int replicaCount(ServiceDefinition service) {
        Object deploy = service.configuration().get("deploy");
        if (deploy instanceof Map<?, ?> map && map.get("replicas") instanceof Number replicas)
            return Math.max(0, replicas.intValue());
        return 1;
    }

    static void validateContainerNameScaling(ServiceDefinition service, int replicas) {
        if (replicas > 1 && service.configuration().get("container_name") != null)
            throw new IllegalArgumentException("Service '" + service.name()
                    + "' cannot be scaled beyond one container when container_name is set.");
    }

    static void validateRunnableService(ServiceDefinition service) {
        if ((service.image() == null || service.image().isBlank()) && service.build() == null)
            throw new IllegalArgumentException("Service '" + service.name()
                    + "' has neither an image nor a build context specified.");
    }

    private static String effectivePullPolicy(ServiceDefinition service, String requested) {
        String policy = requested == null || requested.isBlank() || "policy".equalsIgnoreCase(requested)
                ? stringValue(service.configuration().get("pull_policy"), "missing") : requested;
        policy = policy.toLowerCase(java.util.Locale.ROOT);
        if (policy.equals("if_not_present")) return "missing";
        if (policy.equals("always") || policy.equals("missing") || policy.equals("never") || policy.equals("build")
                || policy.equals("daily") || policy.equals("weekly") || policy.equals("refresh")
                || policy.matches("every_(?:[0-9]+[wdhms])+")) return policy;
        throw new IllegalArgumentException("Unsupported pull policy for service '" + service.name() + "': " + policy);
    }

    private static boolean shouldPullImage(DockerClient client, String image, String policy) {
        return shouldPullImage(client, image, policy, null);
    }

    private static boolean shouldPullImage(DockerClient client, String image, String policy, Object pullRefreshAfter) {
        if ("always".equals(policy)) return true;
        if (isTimeBasedPullPolicy(policy)) {
            if (!imageExists(client, image)) return true;
            Instant lastTagTime = DockerClientFactory.imageLastTagTime(client, image);
            return lastTagTime == null || !Instant.now().isBefore(lastTagTime.plus(pullRefreshDuration(policy, pullRefreshAfter)));
        }
        if (!"missing".equals(policy)) return false;
        if (!imageExists(client, image)) return true;
        if (image.contains("@")) return false;
        int lastSlash = image.lastIndexOf('/');
        int lastColon = image.lastIndexOf(':');
        return lastColon <= lastSlash || "latest".equalsIgnoreCase(image.substring(lastColon + 1));
    }

    private static boolean shouldPullForExplicitPull(DockerClient client, String image, ServiceDefinition service) {
        String configured = stringValue(service.configuration().get("pull_policy"), null);
        if (configured == null || configured.isBlank()) return true;
        String policy = configured.toLowerCase(java.util.Locale.ROOT);
        return switch (policy) {
            case "always", "daily", "weekly", "refresh" -> true;
            case "missing", "if_not_present" -> shouldPullImage(client, image, "missing");
            case "never", "build" -> false;
            default -> {
                if (policy.matches("every_(?:[0-9]+[wdhms])+")) yield true;
                throw new IllegalArgumentException("Unsupported pull policy for service '" + service.name() + "': " + policy);
            }
        };
    }

    static boolean isTimeBasedPullPolicy(String policy) {
        return "daily".equals(policy) || "weekly".equals(policy) || "refresh".equals(policy)
                || policy != null && policy.matches("every_(?:[0-9]+[wdhms])+" );
    }

    static java.time.Duration pullRefreshDuration(String policy, Object configuredRefreshAfter) {
        if ("refresh".equals(policy) && configuredRefreshAfter != null && !String.valueOf(configuredRefreshAfter).isBlank())
            return parsePullRefreshDuration(String.valueOf(configuredRefreshAfter));
        if ("daily".equals(policy)) return java.time.Duration.ofDays(1);
        if ("weekly".equals(policy)) return java.time.Duration.ofDays(7);
        if ("refresh".equals(policy)) return java.time.Duration.ZERO;
        if (policy != null && policy.startsWith("every_")) return parsePullRefreshDuration(policy.substring(6));
        throw new IllegalArgumentException("Unsupported pull policy: " + policy);
    }

    private static java.time.Duration parsePullRefreshDuration(String value) {
        java.util.regex.Matcher matcher = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)(w|d|h|m|s)").matcher(value);
        BigDecimal seconds = BigDecimal.ZERO;
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() != cursor) throw new IllegalArgumentException("Invalid pull refresh duration: " + value);
            BigDecimal multiplier = switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
                case "w" -> BigDecimal.valueOf(604_800);
                case "d" -> BigDecimal.valueOf(86_400);
                case "h" -> BigDecimal.valueOf(3_600);
                case "m" -> BigDecimal.valueOf(60);
                default -> BigDecimal.ONE;
            };
            seconds = seconds.add(new BigDecimal(matcher.group(1)).multiply(multiplier));
            cursor = matcher.end();
        }
        if (cursor == 0 || cursor != value.length())
            throw new IllegalArgumentException("Invalid pull refresh duration: " + value);
        try {
            BigDecimal nanos = seconds.multiply(BigDecimal.valueOf(1_000_000_000L));
            return java.time.Duration.ofNanos(nanos.toBigIntegerExact().longValueExact());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Pull refresh duration is outside the supported range: " + value, exception);
        }
    }

    private static boolean imageExists(DockerClient client, String image) {
        return client.listImagesCmd().exec().stream().anyMatch(candidate -> candidate.getRepoTags() != null
                && java.util.Arrays.asList(candidate.getRepoTags()).contains(image));
    }

    private static void ensureServiceContainers(DockerClient client, ComposeProjectContext context, ComposeProject project,
                                                ServiceDefinition service, int replicas, boolean forceRecreate,
                                                boolean noRecreate, boolean renewAnonVolumes, boolean start,
                                                int stopTimeoutSeconds, ILogConsumer logConsumer) {
        validateRunnableService(service);
        validateContainerNameScaling(service, replicas);
        validateComposeLabels(stringMap(service.configuration().get("labels")));
        if (replicas < 0) throw new IllegalArgumentException("Replica count must not be negative for service '" + service.name() + "'.");
        int effectiveStopTimeout = service.configuration().get("stop_grace_period") == null ? stopTimeoutSeconds
                : parseDurationSeconds(String.valueOf(service.configuration().get("stop_grace_period")));
        List<Container> existing = projectContainers(client, context.projectName(), List.of(service.name()), true).stream()
                .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                .sorted(containerOrder()).toList();
        String image = service.image() == null || service.image().isBlank()
                ? context.projectName() + "-" + service.name() : service.image();
        String networkServiceId = networkModeService(service) == null ? ""
                : "-" + dockerNetworkMode(client, context.projectName(), service).substring("container:".length());
        String hash = configHash(service) + networkServiceId;
        boolean configurationChanged = existing.stream()
                .anyMatch(container -> !hash.equals(label(container, ComposeConstants.CONFIG_HASH_LABEL)));
        boolean recreate = forceRecreate || configurationChanged && !noRecreate;
        Map<Integer, Map<String, String>> anonymousVolumes = new LinkedHashMap<>();
        if (recreate) {
            if (!renewAnonVolumes) {
                List<com.github.dockerjava.api.model.Bind> configuredBinds = serviceBinds(project, context.projectName(), service);
                java.util.Set<String> configuredTargets = configuredBinds.stream()
                        .map(bind -> bind.getVolume().getPath())
                        .collect(java.util.stream.Collectors.toSet());
                java.util.Set<String> anonymousTargets = configuredBinds.stream()
                        .filter(bind -> bind.getPath() == null || bind.getPath().isBlank())
                        .map(bind -> bind.getVolume().getPath())
                        .collect(java.util.stream.Collectors.toSet());
                var imageConfig = client.inspectImageCmd(image).exec().getConfig();
                if (imageConfig != null && imageConfig.getVolumes() != null)
                    imageConfig.getVolumes().keySet().stream().filter(target -> !configuredTargets.contains(target))
                            .forEach(anonymousTargets::add);
                for (Container container : existing) {
                    if (container.getMounts() == null) continue;
                    Map<String, String> volumes = new LinkedHashMap<>();
                    for (var mount : container.getMounts()) {
                        if (mount.getDestination() != null && mount.getName() != null
                                && anonymousTargets.contains(mount.getDestination()))
                            volumes.put(mount.getDestination(), mount.getName());
                    }
                    if (!volumes.isEmpty()) anonymousVolumes.put(replicaNumber(container), volumes);
                }
            }
            for (Container container : existing) {
                if (isRunning(container)) client.stopContainerCmd(container.getId()).withTimeout(effectiveStopTimeout).exec();
                client.removeContainerCmd(container.getId()).withForce(false).withRemoveVolumes(false).exec();
            }
            existing = List.of();
        } else {
            if (existing.size() > replicas) {
                for (Container container : existing.subList(replicas, existing.size())) {
                    if (isRunning(container)) client.stopContainerCmd(container.getId()).withTimeout(effectiveStopTimeout).exec();
                    client.removeContainerCmd(container.getId()).withForce(false).withRemoveVolumes(false).exec();
                }
                existing = existing.subList(0, replicas);
            }
            if (start) existing.forEach(container -> {
                if (!isRunning(container)) client.startContainerCmd(container.getId()).exec();
            });
        }
        if (existing.size() == replicas) return;

        for (int index = existing.size() + 1; index <= replicas; index++) {
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put(ComposeConstants.PROJECT_LABEL, context.projectName());
            labels.put(ComposeConstants.SERVICE_LABEL, service.name());
            labels.put(ComposeConstants.CONTAINER_NUMBER_LABEL, Integer.toString(index));
            labels.put(ComposeConstants.ONE_OFF_LABEL, "false");
            labels.put(ComposeConstants.CONFIG_HASH_LABEL, hash);
            labels.put(ComposeConstants.VERSION_LABEL, "3.0.0");
            labels.put(ComposeConstants.WORKING_DIR_LABEL, project.sourceFile().getParent().toString());
            labels.put(ComposeConstants.CONFIG_FILES_LABEL, project.sourceFile().toString());
            labels.putAll(stringMap(service.configuration().get("labels")));

            com.github.dockerjava.api.model.HostConfig host = serviceHostConfig(client, project, context.projectName(), service,
                    anonymousVolumes.getOrDefault(index, Map.of()));
            var command = client.createContainerCmd(image).withName(containerName(context.projectName(), service, replicas, index))
                    .withLabels(labels).withHostConfig(host);
            com.github.dockerjava.api.model.HealthCheck healthcheck = serviceHealthcheck(service);
            if (healthcheck != null) command.withHealthcheck(healthcheck);
            List<String> environment = stringMap(service.configuration().get("environment")).entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
            if (!environment.isEmpty()) command.withEnv(environment);
            List<String> cmd = asStringList(service.configuration().get("command"));
            if (!cmd.isEmpty()) command.withCmd(cmd);
            List<String> entrypoint = asStringList(service.configuration().get("entrypoint"));
            if (!entrypoint.isEmpty()) command.withEntrypoint(entrypoint);
            if (service.configuration().get("working_dir") != null) command.withWorkingDir(String.valueOf(service.configuration().get("working_dir")));
            if (service.configuration().get("user") != null) command.withUser(String.valueOf(service.configuration().get("user")));
            if (service.configuration().get("hostname") != null) command.withHostName(String.valueOf(service.configuration().get("hostname")));
            if (service.configuration().get("domainname") != null) command.withDomainName(String.valueOf(service.configuration().get("domainname")));
            if (service.configuration().get("stop_signal") != null)
                command.withStopSignal(String.valueOf(service.configuration().get("stop_signal")));
            if (service.configuration().get("stop_grace_period") != null)
                command.withStopTimeout(parseDurationSeconds(String.valueOf(service.configuration().get("stop_grace_period"))));
            if (service.configuration().get("tty") instanceof Boolean tty) command.withTty(tty);
            if (service.configuration().get("stdin_open") instanceof Boolean stdin) command.withStdinOpen(stdin);
            if (networkModeService(service) == null) {
                String primaryNetwork = serviceNetworks(service).getFirst();
                List<String> primaryAliases = networkAliases(service, primaryNetwork, true);
                if (!primaryAliases.isEmpty()) command.withAliases(primaryAliases);
                applyPrimaryNetworkOptions(command, service, primaryNetwork);
            }
            List<com.github.dockerjava.api.model.PortBinding> bindings = servicePorts(service);
            if (!bindings.isEmpty()) command.withPortBindings(bindings);
            List<com.github.dockerjava.api.model.ExposedPort> exposed = serviceExposedPorts(service);
            if (!exposed.isEmpty()) command.withExposedPorts(exposed);
            String id = command.exec().getId();
            connectAdditionalNetworks(client, context.projectName(), project, service, id, true);
            if (start) client.startContainerCmd(id).exec();
            if (logConsumer != null) logConsumer.onStatus(service.name(), (start ? "Started " : "Created ") + containerName(context.projectName(), service, replicas, index));
        }
    }

    private static String primaryNetwork(String projectName, ComposeProject project, ServiceDefinition service) {
        return engineNetworkName(projectName, project, serviceNetworks(service).getFirst());
    }

    private static List<String> serviceNetworks(ServiceDefinition service) {
        Object configured = service.configuration().get("networks");
        List<String> names;
        if (configured instanceof Map<?, ?> map) names = map.keySet().stream().map(String::valueOf).toList();
        else names = asStringList(configured);
        return names.isEmpty() ? List.of("default") : List.copyOf(names);
    }

    private static Map<String, Object> networkDefinition(ComposeProject project, String name) {
        Object value = project.networkDefinitions().get(name);
        return value instanceof Map<?, ?> map ? stringKeyMap(map) : Map.of();
    }

    private static Map<String, Object> volumeDefinition(ComposeProject project, String name) {
        Object value = project.volumeDefinitions().get(name);
        return value instanceof Map<?, ?> map ? stringKeyMap(map) : Map.of();
    }

    private static boolean externalResource(Map<String, Object> definition) {
        return Boolean.TRUE.equals(definition.get("external")) || definition.get("external") instanceof Map<?, ?>;
    }

    private static String resourceName(Map<String, Object> definition, String logicalName) {
        String name = stringValue(definition.get("name"), null);
        if (name == null && definition.get("external") instanceof Map<?, ?> external)
            name = stringValue(external.get("name"), null);
        return name == null || name.isBlank() ? logicalName : name;
    }

    private static String engineNetworkName(String projectName, ComposeProject project, String network) {
        Map<String, Object> definition = networkDefinition(project, network);
        String name = resourceName(definition, network);
        return externalResource(definition) || definition.containsKey("name") ? name : projectName + "_" + name;
    }

    private static String engineVolumeName(String projectName, ComposeProject project, String volume) {
        Map<String, Object> definition = volumeDefinition(project, volume);
        String name = resourceName(definition, volume);
        return externalResource(definition) || definition.containsKey("name") ? name : projectName + "_" + name;
    }

    private static Map<String, String> resourceLabels(Map<String, Object> definition, String projectName) {
        Map<String, String> labels = new LinkedHashMap<>(stringMap(definition.get("labels")));
        validateComposeLabels(labels);
        labels.put(ComposeConstants.PROJECT_LABEL, projectName);
        return labels;
    }

    static void validateComposeLabels(Map<String, String> labels) {
        for (String name : labels.keySet()) {
            if (name.equals("com.docker.compose") || name.startsWith("com.docker.compose."))
                throw new IllegalArgumentException("Label prefix 'com.docker.compose' is reserved by Docker Compose: " + name + ".");
        }
    }

    private static void validateServiceLabelConfiguration(ServiceDefinition service) {
        validateServiceLabels(service);
        validateBuildLabels(service);
    }

    private static void validateServiceLabels(ServiceDefinition service) {
        validateComposeLabels(stringMap(service.configuration().get("labels")));
    }

    private static void validateBuildLabels(ServiceDefinition service) {
        if (service.build() instanceof Map<?, ?> build)
            validateComposeLabels(stringMap(build.get("labels")));
    }

    private static com.github.dockerjava.api.model.Network.Ipam networkIpam(Map<String, Object> options) {
        var ipam = new com.github.dockerjava.api.model.Network.Ipam();
        if (options.get("driver") != null) ipam.withDriver(String.valueOf(options.get("driver")));
        if (options.get("config") instanceof List<?> configs) {
            List<com.github.dockerjava.api.model.Network.Ipam.Config> subnets = new ArrayList<>();
            for (Object item : configs) if (item instanceof Map<?, ?> map) {
                Map<String, Object> config = stringKeyMap(map);
                var subnet = new com.github.dockerjava.api.model.Network.Ipam.Config();
                if (config.get("subnet") != null) subnet.withSubnet(String.valueOf(config.get("subnet")));
                if (config.get("ip_range") != null) subnet.withIpRange(String.valueOf(config.get("ip_range")));
                if (config.get("gateway") != null) subnet.withGateway(String.valueOf(config.get("gateway")));
                subnets.add(subnet);
            }
            ipam.withConfig(subnets);
        }
        return ipam;
    }

    private static Map<String, Object> serviceNetworkOptions(ServiceDefinition service, String network) {
        Object configured = service.configuration().get("networks");
        if (!(configured instanceof Map<?, ?> map)) return Map.of();
        Object options = map.get(network);
        if (!(options instanceof Map<?, ?> optionMap)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        optionMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static List<String> networkAliases(ServiceDefinition service, String network, boolean includeServiceName) {
        java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>();
        if (includeServiceName) aliases.add(service.name());
        aliases.addAll(asStringList(serviceNetworkOptions(service, network).get("aliases")));
        return List.copyOf(aliases);
    }

    private static void applyPrimaryNetworkOptions(com.github.dockerjava.api.command.CreateContainerCmd command,
                                                   ServiceDefinition service, String network) {
        Map<String, Object> options = serviceNetworkOptions(service, network);
        String ipv4 = stringValue(options.get("ipv4_address"), null);
        String ipv6 = stringValue(options.get("ipv6_address"), null);
        String mac = stringValue(options.get("mac_address"),
                network.equals(serviceNetworks(service).getFirst())
                        ? stringValue(service.configuration().get("mac_address"), null) : null);
        if (ipv4 != null) command.withIpv4Address(ipv4);
        if (ipv6 != null) command.withIpv6Address(ipv6);
        if (mac != null) command.withMacAddress(mac);
    }

    private static void connectAdditionalNetworks(DockerClient client, String projectName,
                                                   ComposeProject project, ServiceDefinition service, String containerId,
                                                   boolean includeServiceName) {
        List<String> networks = serviceNetworks(service);
        if (service.configuration().get("network_mode") != null && service.configuration().containsKey("networks")) {
            throw new IllegalArgumentException("Service '" + service.name()
                    + "' cannot combine network_mode with networks.");
        }
        String networkMode = stringValue(service.configuration().get("network_mode"), "");
        if (!networkMode.isBlank()) return;
        Map<String, String> idsByName = new LinkedHashMap<>();
        client.listNetworksCmd().exec().forEach(network -> idsByName.put(network.getName(), network.getId()));
        for (int index = 1; index < networks.size(); index++) {
            String logicalName = networks.get(index);
            String engineName = engineNetworkName(projectName, project, logicalName);
            String networkId = idsByName.get(engineName);
            if (networkId == null) throw new IllegalStateException("Docker network '" + engineName + "' was not created.");
            Map<String, Object> options = serviceNetworkOptions(service, logicalName);
            var endpoint = new com.github.dockerjava.api.model.ContainerNetwork();
            List<String> aliases = networkAliases(service, logicalName, includeServiceName);
            if (!aliases.isEmpty()) endpoint.withAliases(aliases);
            if (options.get("mac_address") != null) endpoint.withMacAddress(String.valueOf(options.get("mac_address")));
            String ipv4 = stringValue(options.get("ipv4_address"), null);
            String ipv6 = stringValue(options.get("ipv6_address"), null);
            if (ipv4 != null || ipv6 != null) {
                var ipam = new com.github.dockerjava.api.model.ContainerNetwork.Ipam();
                if (ipv4 != null) ipam.withIpv4Address(ipv4);
                if (ipv6 != null) ipam.withIpv6Address(ipv6);
                endpoint.withIpamConfig(ipam);
            }
            client.connectToNetworkCmd().withNetworkId(networkId).withContainerId(containerId)
                    .withContainerNetwork(endpoint).exec();
        }
    }

    private static com.github.dockerjava.api.model.HostConfig serviceHostConfig(DockerClient client, ComposeProject project, String projectName,
                                                                                ServiceDefinition service, Map<String, String> anonymousVolumes) {
        Map<String, Object> config = service.configuration();
        var host = com.github.dockerjava.api.model.HostConfig.newHostConfig();
        String configuredNetworkMode = stringValue(config.get("network_mode"), "");
        host.withNetworkMode(configuredNetworkMode.isBlank() ? primaryNetwork(projectName, project, service)
                : dockerNetworkMode(client, projectName, service));
        List<com.github.dockerjava.api.model.Bind> binds = serviceBinds(project, projectName, service);
        if (!anonymousVolumes.isEmpty()) {
            List<com.github.dockerjava.api.model.Bind> configuredBinds = new ArrayList<>(binds);
            java.util.Set<String> reusedTargets = anonymousVolumes.keySet();
            binds.removeIf(bind -> (bind.getPath() == null || bind.getPath().isBlank())
                    && reusedTargets.contains(bind.getVolume().getPath()));
            List<com.github.dockerjava.api.model.Mount> mounts = anonymousVolumes.entrySet().stream()
                    .map(entry -> {
                        boolean readOnly = configuredBinds.stream()
                                .anyMatch(bind -> (bind.getPath() == null || bind.getPath().isBlank())
                                        && entry.getKey().equals(bind.getVolume().getPath())
                                        && bind.getAccessMode() == com.github.dockerjava.api.model.AccessMode.ro);
                        return new com.github.dockerjava.api.model.Mount()
                                .withType(com.github.dockerjava.api.model.MountType.VOLUME)
                                .withSource(entry.getValue()).withTarget(entry.getKey()).withReadOnly(readOnly);
                    })
                    .toList();
            host.withMounts(mounts);
        }
        if (!binds.isEmpty()) host.withBinds(binds);
        applyVolumesFrom(client, project, projectName, service, host);
        if (config.get("init") instanceof Boolean value) host.withInit(value);
        com.github.dockerjava.api.model.RestartPolicy restart = effectiveRestartPolicy(config);
        if (restart != null) host.withRestartPolicy(restart);
        if (config.get("privileged") instanceof Boolean value) host.withPrivileged(value);
        if (config.get("read_only") instanceof Boolean value) host.withReadonlyRootfs(value);
        if (config.get("ipc") != null) host.withIpcMode(String.valueOf(config.get("ipc")));
        if (config.get("pid") != null) host.withPidMode(String.valueOf(config.get("pid")));
        if (config.get("cgroup_parent") != null) host.withCgroupParent(String.valueOf(config.get("cgroup_parent")));
        if (config.get("shm_size") != null) host.withShmSize(parseByteSize(String.valueOf(config.get("shm_size"))));
        applyRuntimeOptions(host, config);
        applyDnsConfig(host, config);
        applyLoggingConfig(host, config);

        List<String> extraHosts = extraHostEntries(config.get("extra_hosts"));
        if (!extraHosts.isEmpty()) host.withExtraHosts(extraHosts.toArray(String[]::new));
        List<String> tmpfs = asStringList(config.get("tmpfs"));
        if (!tmpfs.isEmpty()) {
            Map<String, String> mounts = new LinkedHashMap<>();
            for (String mount : tmpfs) {
                String[] parts = mount.split(":", -1);
                mounts.put(parts[0], parts.length > 1 ? parts[parts.length - 1] : "");
            }
            host.withTmpFs(mounts);
        }
        List<String> capAdd = asStringList(config.get("cap_add"));
        if (!capAdd.isEmpty()) host.withCapAdd(capAdd.stream().map(ComposeService::capability)
                .toArray(com.github.dockerjava.api.model.Capability[]::new));
        List<String> capDrop = asStringList(config.get("cap_drop"));
        if (!capDrop.isEmpty()) host.withCapDrop(capDrop.stream().map(ComposeService::capability)
                .toArray(com.github.dockerjava.api.model.Capability[]::new));
        List<com.github.dockerjava.api.model.Device> devices = deviceMappings(config.get("devices"));
        if (!devices.isEmpty()) host.withDevices(devices);
        applyDeviceCgroupRules(host, config);
        List<String> securityOptions = asStringList(config.get("security_opt"));
        if (!securityOptions.isEmpty()) host.withSecurityOpts(securityOptions);
        List<String> groups = asStringList(config.get("group_add"));
        if (!groups.isEmpty()) host.withGroupAdd(groups);

        Map<String, String> sysctls = stringMap(config.get("sysctls"));
        if (!sysctls.isEmpty()) host.withSysctls(sysctls);
        applyBlockIoConfig(host, config.get("blkio_config"));
        applyResourceConfig(host, config);
        return host;
    }

    private static void applyBlockIoConfig(com.github.dockerjava.api.model.HostConfig host, Object configured) {
        if (configured == null) return;
        if (!(configured instanceof Map<?, ?> options)) {
            throw new IllegalArgumentException("Service 'blkio_config' must be a mapping.");
        }
        if (options.get("weight") != null)
            host.withBlkioWeight(Math.toIntExact(parseLong(options.get("weight"), "blkio_config.weight")));
        List<com.github.dockerjava.api.model.BlkioWeightDevice> weights = blkioWeightDevices(options.get("weight_device"));
        if (!weights.isEmpty()) host.withBlkioWeightDevice(weights);
        List<com.github.dockerjava.api.model.BlkioRateDevice> readBps = blkioRateDevices(options.get("device_read_bps"), "blkio_config.device_read_bps", true);
        if (!readBps.isEmpty()) host.withBlkioDeviceReadBps(readBps);
        List<com.github.dockerjava.api.model.BlkioRateDevice> writeBps = blkioRateDevices(options.get("device_write_bps"), "blkio_config.device_write_bps", true);
        if (!writeBps.isEmpty()) host.withBlkioDeviceWriteBps(writeBps);
        List<com.github.dockerjava.api.model.BlkioRateDevice> readIops = blkioRateDevices(options.get("device_read_iops"), "blkio_config.device_read_iops", false);
        if (!readIops.isEmpty()) host.withBlkioDeviceReadIOps(readIops);
        List<com.github.dockerjava.api.model.BlkioRateDevice> writeIops = blkioRateDevices(options.get("device_write_iops"), "blkio_config.device_write_iops", false);
        if (!writeIops.isEmpty()) host.withBlkioDeviceWriteIOps(writeIops);
    }

    private static List<com.github.dockerjava.api.model.BlkioWeightDevice> blkioWeightDevices(Object configured) {
        if (configured == null) return List.of();
        if (!(configured instanceof List<?> entries))
            throw new IllegalArgumentException("Service 'blkio_config.weight_device' must be a list.");
        List<com.github.dockerjava.api.model.BlkioWeightDevice> devices = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> values))
                throw new IllegalArgumentException("Each blkio_config.weight_device entry must be a mapping.");
            String path = stringValue(values.get("path"), null);
            if (path == null || path.isBlank() || values.get("weight") == null)
                throw new IllegalArgumentException("Each blkio_config.weight_device entry requires path and weight.");
            devices.add(new com.github.dockerjava.api.model.BlkioWeightDevice().withPath(path)
                    .withWeight(Math.toIntExact(parseLong(values.get("weight"), "blkio_config.weight_device.weight"))));
        }
        return List.copyOf(devices);
    }

    private static List<com.github.dockerjava.api.model.BlkioRateDevice> blkioRateDevices(Object configured, String field,
                                                                                          boolean bytesPerSecond) {
        if (configured == null) return List.of();
        if (!(configured instanceof List<?> entries))
            throw new IllegalArgumentException("Service '" + field + "' must be a list.");
        List<com.github.dockerjava.api.model.BlkioRateDevice> devices = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> values))
                throw new IllegalArgumentException("Each " + field + " entry must be a mapping.");
            String path = stringValue(values.get("path"), null);
            Object rate = values.get("rate");
            if (path == null || path.isBlank() || rate == null)
                throw new IllegalArgumentException("Each " + field + " entry requires path and rate.");
            long parsedRate = bytesPerSecond ? parseByteSize(String.valueOf(rate)) : parseLong(rate, field + ".rate");
            devices.add(new com.github.dockerjava.api.model.BlkioRateDevice().withPath(path).withRate(parsedRate));
        }
        return List.copyOf(devices);
    }

    private static void applyRuntimeOptions(com.github.dockerjava.api.model.HostConfig host, Map<String, Object> config) {
        if (config.get("userns_mode") != null) host.withUsernsMode(String.valueOf(config.get("userns_mode")));
        if (config.get("runtime") != null) host.withRuntime(String.valueOf(config.get("runtime")));
        Object configuredStorageOptions = config.get("storage_opt");
        if (configuredStorageOptions != null) {
            if (!(configuredStorageOptions instanceof Map<?, ?> storageOptions)) {
                throw new IllegalArgumentException("Service 'storage_opt' must be a mapping.");
            }
            host.withStorageOpt(stringMap(storageOptions));
        }
    }

    private static void applyVolumesFrom(DockerClient client, ComposeProject project, String projectName,
                                         ServiceDefinition service,
                                         com.github.dockerjava.api.model.HostConfig host) {
        List<com.github.dockerjava.api.model.VolumesFrom> volumesFrom = new ArrayList<>();
        for (VolumeFromReference reference : volumeFromReferences(service.configuration().get("volumes_from"))) {
            String containerName;
            if (reference.externalContainer()) {
                containerName = reference.source();
            } else {
                ServiceDefinition sourceService = project.services().get(reference.source());
                if (sourceService == null) {
                    throw new IllegalArgumentException("Service '" + service.name() + "' volumes_from references unknown service '"
                            + reference.source() + "'.");
                }
                Container sourceContainer = projectContainers(client, projectName, List.of(sourceService.name()), true).stream()
                        .sorted(containerOrder()).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Service '" + service.name()
                                + "' volumes_from requires service '" + sourceService.name() + "' to have a container first."));
                String[] names = sourceContainer.getNames();
                containerName = names == null || names.length == 0 ? sourceContainer.getId()
                        : names[0].startsWith("/") ? names[0].substring(1) : names[0];
            }
            volumesFrom.add(new com.github.dockerjava.api.model.VolumesFrom(containerName, reference.accessMode()));
        }
        if (!volumesFrom.isEmpty()) host.withVolumesFrom(volumesFrom);
    }

    private static void applyDnsConfig(com.github.dockerjava.api.model.HostConfig host, Map<String, Object> config) {
        List<String> dns = asStringList(config.get("dns"));
        if (!dns.isEmpty()) host.withDns(dns);
        List<String> dnsOptions = asStringList(config.get("dns_opt"));
        if (!dnsOptions.isEmpty()) host.withDnsOptions(dnsOptions);
        List<String> dnsSearch = asStringList(config.get("dns_search"));
        if (!dnsSearch.isEmpty()) host.withDnsSearch(dnsSearch);
    }

    private static void applyDeviceCgroupRules(com.github.dockerjava.api.model.HostConfig host, Map<String, Object> config) {
        List<String> rules = asStringList(config.get("device_cgroup_rules"));
        if (!rules.isEmpty()) host.withDeviceCgroupRules(rules);
    }

    private static void applyLoggingConfig(com.github.dockerjava.api.model.HostConfig host, Map<String, Object> config) {
        Object configured = config.get("logging");
        if (configured == null) return;
        if (!(configured instanceof Map<?, ?> logging)) {
            throw new IllegalArgumentException("Service 'logging' must be a mapping.");
        }
        String driverName = stringValue(logging.get("driver"), null);
        com.github.dockerjava.api.model.LogConfig.LoggingType driver = null;
        if (driverName != null && !driverName.isBlank()) {
            driver = com.github.dockerjava.api.model.LogConfig.LoggingType.fromValue(driverName);
            if (driver == null) {
                throw new IllegalArgumentException("Logging driver '" + driverName
                        + "' is not supported by the current Docker Java API model.");
            }
        }
        Object optionsValue = logging.get("options");
        Map<String, String> options = Map.of();
        if (optionsValue != null) {
            if (!(optionsValue instanceof Map<?, ?> optionMap)) {
                throw new IllegalArgumentException("Service 'logging.options' must be a mapping.");
            }
            Map<String, String> parsedOptions = new LinkedHashMap<>();
            optionMap.forEach((key, value) -> {
                if (value != null) parsedOptions.put(String.valueOf(key), String.valueOf(value));
            });
            options = parsedOptions;
        }
        host.withLogConfig(new com.github.dockerjava.api.model.LogConfig(driver, options));
    }

    private static void applyResourceConfig(com.github.dockerjava.api.model.HostConfig host, Map<String, Object> config) {
        Object deployMemoryLimit = deployResourceValue(config, "limits", "memory");
        Object serviceMemoryLimit = config.get("mem_limit");
        requireConsistentResource(serviceMemoryLimit, deployMemoryLimit, "mem_limit", "deploy.resources.limits.memory",
                value -> parseByteSize(String.valueOf(value)));
        Object memoryLimit = serviceMemoryLimit != null ? serviceMemoryLimit : deployMemoryLimit;
        if (memoryLimit != null) host.withMemory(parseByteSize(String.valueOf(memoryLimit)));
        if (config.get("memswap_limit") != null) host.withMemorySwap(parseByteSize(String.valueOf(config.get("memswap_limit"))));
        Object deployMemoryReservation = deployResourceValue(config, "reservations", "memory");
        Object serviceMemoryReservation = config.get("mem_reservation");
        requireConsistentResource(serviceMemoryReservation, deployMemoryReservation, "mem_reservation", "deploy.resources.reservations.memory",
                value -> parseByteSize(String.valueOf(value)));
        Object memoryReservation = serviceMemoryReservation != null ? serviceMemoryReservation : deployMemoryReservation;
        if (memoryReservation != null) host.withMemoryReservation(parseByteSize(String.valueOf(memoryReservation)));
        List<com.github.dockerjava.api.model.DeviceRequest> deviceRequests = new ArrayList<>(deployDeviceRequests(config));
        deviceRequests.addAll(serviceGpuRequests(config.get("gpus")));
        if (!deviceRequests.isEmpty()) host.withDeviceRequests(deviceRequests);
        if (config.get("mem_swappiness") != null) host.withMemorySwappiness(parseLong(config.get("mem_swappiness"), "mem_swappiness"));
        if (config.get("cpu_shares") != null) host.withCpuShares(Math.toIntExact(parseLong(config.get("cpu_shares"), "cpu_shares")));
        if (config.get("cpuset") != null) host.withCpusetCpus(String.valueOf(config.get("cpuset")));
        if (config.get("cpu_quota") != null) host.withCpuQuota(parseLong(config.get("cpu_quota"), "cpu_quota"));
        if (config.get("cpu_period") != null) host.withCpuPeriod(parseLong(config.get("cpu_period"), "cpu_period"));
        Object deployCpusLimit = deployResourceValue(config, "limits", "cpus");
        Object serviceCpus = config.get("cpus");
        requireConsistentResource(serviceCpus, deployCpusLimit, "cpus", "deploy.resources.limits.cpus", ComposeService::parseNanoCpus);
        Object cpus = serviceCpus != null ? serviceCpus : deployCpusLimit;
        if (cpus != null) host.withNanoCPUs(parseNanoCpus(cpus));
        Object deployPidsLimit = deployResourceValue(config, "limits", "pids");
        Object servicePidsLimit = config.get("pids_limit");
        requireConsistentResource(servicePidsLimit, deployPidsLimit, "pids_limit", "deploy.resources.limits.pids",
                value -> parseLong(value, "pids_limit"));
        Object pidsLimit = servicePidsLimit != null ? servicePidsLimit : deployPidsLimit;
        if (pidsLimit != null) host.withPidsLimit(parseLong(pidsLimit, "pids_limit"));
        if (config.get("cpu_count") != null) host.withCpuCount(parseLong(config.get("cpu_count"), "cpu_count"));
        if (config.get("cpu_percent") != null) host.withCpuPercent(parseLong(config.get("cpu_percent"), "cpu_percent"));
        if (config.get("oom_kill_disable") instanceof Boolean value) host.withOomKillDisable(value);
        if (config.get("oom_score_adj") != null) host.withOomScoreAdj(Math.toIntExact(parseLong(config.get("oom_score_adj"), "oom_score_adj")));
        if (config.get("uts") != null) host.withUtSMode(String.valueOf(config.get("uts")));
        applyUlimits(host, config.get("ulimits"));
    }

    private static List<com.github.dockerjava.api.model.DeviceRequest> deployDeviceRequests(Map<String, Object> config) {
        Object configured = deployResourceValue(config, "reservations", "devices");
        if (configured == null) return List.of();
        if (!(configured instanceof List<?> entries)) {
            throw new IllegalArgumentException("Service 'deploy.resources.reservations.devices' must be a list.");
        }
        List<com.github.dockerjava.api.model.DeviceRequest> requests = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("Each deploy.resources.reservations.devices entry must be a mapping.");
            }
            List<String> capabilities = asStringList(values.get("capabilities"));
            if (capabilities.isEmpty()) {
                throw new IllegalArgumentException("Each deploy.resources.reservations.devices entry requires capabilities.");
            }
            Object countValue = values.get("count");
            Object idsValue = values.get("device_ids");
            if (countValue != null && idsValue != null) {
                throw new IllegalArgumentException("deploy.resources.reservations.devices count and device_ids are mutually exclusive.");
            }
            List<String> deviceIds = asStringList(idsValue);
            Integer count = null;
            if (countValue != null) {
                if ("all".equalsIgnoreCase(String.valueOf(countValue))) count = -1;
                else {
                    long parsedCount = parseLong(countValue, "deploy.resources.reservations.devices.count");
                    if (parsedCount < 0) {
                        throw new IllegalArgumentException("deploy.resources.reservations.devices.count must be non-negative or 'all'.");
                    }
                    count = Math.toIntExact(parsedCount);
                }
            } else if (idsValue == null) {
                count = -1;
            }
            Map<String, String> options = stringMap(values.get("options"));
            var request = new com.github.dockerjava.api.model.DeviceRequest()
                    .withDriver(stringValue(values.get("driver"), null))
                    .withCapabilities(List.of(capabilities));
            if (count != null) request.withCount(count);
            if (idsValue != null) request.withDeviceIds(deviceIds);
            if (!options.isEmpty()) request.withOptions(options);
            requests.add(request);
        }
        return List.copyOf(requests);
    }

    private static List<com.github.dockerjava.api.model.DeviceRequest> serviceGpuRequests(Object configured) {
        if (configured == null) return List.of();
        if ("all".equalsIgnoreCase(String.valueOf(configured))) {
            return List.of(new com.github.dockerjava.api.model.DeviceRequest()
                    .withCount(-1).withCapabilities(List.of(List.of("gpu"))));
        }
        if (!(configured instanceof List<?> entries)) {
            throw new IllegalArgumentException("Service 'gpus' must be 'all' or a list of mappings.");
        }
        List<com.github.dockerjava.api.model.DeviceRequest> requests = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("Each service 'gpus' entry must be a mapping.");
            }
            List<String> capabilities = asStringList(values.get("capabilities"));
            if (capabilities.isEmpty()) capabilities = List.of("gpu");
            Object countValue = values.get("count");
            Object idsValue = values.get("device_ids");
            if (countValue != null && idsValue != null) {
                throw new IllegalArgumentException("Service 'gpus' count and device_ids are mutually exclusive.");
            }
            var request = new com.github.dockerjava.api.model.DeviceRequest()
                    .withDriver(stringValue(values.get("driver"), null))
                    .withCapabilities(List.of(capabilities));
            if (countValue != null) {
                if ("all".equalsIgnoreCase(String.valueOf(countValue))) request.withCount(-1);
                else {
                    long count = parseLong(countValue, "gpus.count");
                    if (count < 0) throw new IllegalArgumentException("Service 'gpus.count' must be non-negative or 'all'.");
                    request.withCount(Math.toIntExact(count));
                }
            } else if (idsValue != null) {
                request.withDeviceIds(asStringList(idsValue));
            } else {
                request.withCount(-1);
            }
            Map<String, String> options = stringMap(values.get("options"));
            if (!options.isEmpty()) request.withOptions(options);
            requests.add(request);
        }
        return List.copyOf(requests);
    }

    private static void applyUlimits(com.github.dockerjava.api.model.HostConfig host, Object configured) {
        if (configured == null) return;
        if (!(configured instanceof Map<?, ?> limits)) {
            throw new IllegalArgumentException("Service 'ulimits' must be a mapping.");
        }
        List<com.github.dockerjava.api.model.Ulimit> parsed = new ArrayList<>();
        limits.forEach((nameValue, limitValue) -> {
            String name = String.valueOf(nameValue);
            long soft;
            long hard;
            if (limitValue instanceof Map<?, ?> values) {
                if (!values.containsKey("soft") || !values.containsKey("hard")) {
                    throw new IllegalArgumentException("Service 'ulimits." + name + "' must define both soft and hard.");
                }
                soft = parseLong(values.get("soft"), "ulimits." + name + ".soft");
                hard = parseLong(values.get("hard"), "ulimits." + name + ".hard");
            } else {
                soft = parseLong(limitValue, "ulimits." + name);
                hard = soft;
            }
            parsed.add(new com.github.dockerjava.api.model.Ulimit(name, soft, hard));
        });
        if (!parsed.isEmpty()) host.withUlimits(parsed);
    }

    private static Object deployResourceValue(Map<String, Object> config, String section, String field) {
        Object deploy = config.get("deploy");
        Object resources = deploy instanceof Map<?, ?> deployMap ? deployMap.get("resources") : null;
        Object values = resources instanceof Map<?, ?> resourceMap ? resourceMap.get(section) : null;
        return values instanceof Map<?, ?> valueMap ? valueMap.get(field) : null;
    }

    private static void requireConsistentResource(Object serviceValue, Object deployValue, String serviceField,
                                                  String deployField, java.util.function.ToLongFunction<Object> parser) {
        if (serviceValue == null || deployValue == null) return;
        long serviceParsed = parser.applyAsLong(serviceValue);
        long deployParsed = parser.applyAsLong(deployValue);
        if (serviceParsed != deployParsed) {
            throw new IllegalArgumentException("'" + serviceField + "' must be consistent with '" + deployField + "'.");
        }
    }

    private static long parseLong(Object value, String field) {
        try {
            return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer for '" + field + "': " + value, exception);
        }
    }

    private static long parseNanoCpus(Object value) {
        try {
            return new java.math.BigDecimal(String.valueOf(value)).movePointRight(9).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid CPU limit for 'cpus': " + value, exception);
        }
    }

    private static com.github.dockerjava.api.model.Capability capability(String value) {
        return com.github.dockerjava.api.model.Capability.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    private static List<String> extraHostEntries(Object value) {
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> entry.getKey() + ":" + entry.getValue()).toList();
        return asStringList(value);
    }

    private static List<com.github.dockerjava.api.model.Device> deviceMappings(Object value) {
        List<com.github.dockerjava.api.model.Device> devices = new ArrayList<>();
        List<?> entries = value instanceof List<?> list ? list : value == null ? List.of() : List.of(value);
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> raw) {
                Map<String, Object> mapping = stringKeyMap(raw);
                String source = stringValue(mapping.get("source"), null);
                String target = stringValue(mapping.get("target"), null);
                if (source == null || source.isBlank() || target == null || target.isBlank())
                    throw new IllegalArgumentException("Long device mappings require non-empty source and target paths.");
                devices.add(new com.github.dockerjava.api.model.Device(source, target,
                        stringValue(mapping.get("permissions"), "rwm")));
            } else {
                devices.add(com.github.dockerjava.api.model.Device.parse(String.valueOf(entry)));
            }
        }
        return List.copyOf(devices);
    }

    private static com.github.dockerjava.api.model.HealthCheck serviceHealthcheck(ServiceDefinition service) {
        if (!(service.configuration().get("healthcheck") instanceof Map<?, ?> raw)) return null;
        Map<String, Object> config = stringKeyMap(raw);
        List<String> test = asStringList(config.get("test"));
        if (Boolean.TRUE.equals(config.get("disable"))) test = List.of("NONE");
        if (test.isEmpty()) return null;
        var healthcheck = new com.github.dockerjava.api.model.HealthCheck().withTest(test);
        if (config.get("retries") instanceof Number retries) healthcheck.withRetries(retries.intValue());
        if (config.get("interval") != null) healthcheck.withInterval(parseDurationNanos(String.valueOf(config.get("interval"))));
        if (config.get("timeout") != null) healthcheck.withTimeout(parseDurationNanos(String.valueOf(config.get("timeout"))));
        if (config.get("start_period") != null) healthcheck.withStartPeriod(parseDurationNanos(String.valueOf(config.get("start_period"))));
        if (config.get("start_interval") != null) healthcheck.withStartInterval(parseDurationNanos(String.valueOf(config.get("start_interval"))));
        return healthcheck;
    }

    private static long parseDurationNanos(String value) {
        String input = value.trim();
        if (input.isEmpty()) throw new IllegalArgumentException("Invalid duration: " + value);
        java.util.regex.Matcher matcher = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)(ns|us|µs|ms|s|m|h)").matcher(input);
        BigDecimal nanos = BigDecimal.ZERO;
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() != cursor) throw new IllegalArgumentException("Invalid duration: " + value);
            long multiplier = switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
                case "ns" -> 1L;
                case "us", "µs" -> 1_000L;
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60_000_000_000L;
                case "h" -> 3_600_000_000_000L;
                default -> throw new IllegalArgumentException("Invalid duration: " + value);
            };
            nanos = nanos.add(new BigDecimal(matcher.group(1)).multiply(BigDecimal.valueOf(multiplier)));
            cursor = matcher.end();
        }
        if (cursor == 0 && input.matches("\\d+(?:\\.\\d+)?")) {
            nanos = new BigDecimal(input).multiply(BigDecimal.valueOf(1_000_000_000L));
            cursor = input.length();
        }
        if (cursor != input.length()) throw new IllegalArgumentException("Invalid duration: " + value);
        try {
            return nanos.toBigInteger().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Duration is outside the supported range: " + value, exception);
        }
    }

    private static int parseDurationSeconds(String value) {
        long nanos = parseDurationNanos(value);
        return Math.toIntExact(Math.floorDiv(Math.addExact(nanos, 999_999_999L), 1_000_000_000L));
    }

    private static void awaitDependencies(DockerClient client, ComposeProject project, String projectName,
                                         ServiceDefinition service, Integer timeoutSeconds,
                                         List<String> activeProfiles) {
        Object rawDependencies = service.configuration().get("depends_on");
        if (rawDependencies == null) return;
        Map<String, ServiceDefinition> definitions = project.services();
        for (String dependencyName : dependencyNames(rawDependencies)) {
            ServiceDefinition dependency = definitions.get(dependencyName);
            if (dependency == null) throw new IllegalArgumentException("Service '" + service.name()
                    + "' depends on unknown service '" + dependencyName + "'.");
            if (!dependencyRequired(service, dependencyName)
                    && !dependency.profiles().isEmpty()
                    && dependency.profiles().stream().noneMatch(activeProfiles::contains)) continue;
            boolean required = dependencyRequired(service, dependencyName);
            String condition = dependencyCondition(rawDependencies, dependencyName);
            List<Container> containers = projectContainers(client, projectName, List.of(dependencyName), true).stream()
                    .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                    .toList();
            if (containers.isEmpty() && !required) {
                LOGGER.warning("Optional dependency '" + dependencyName + "' of service '" + service.name()
                        + "' has no containers; continuing.");
                continue;
            }
            if (containers.isEmpty()) throw new IllegalStateException("Service '" + service.name() + "' depends on '"
                    + dependencyName + "', but that service has no containers.");
            long deadline = timeoutSeconds == null ? Long.MAX_VALUE
                    : System.nanoTime() + TimeUnit.SECONDS.toNanos(positiveTimeout(timeoutSeconds));
            while (true) {
                List<String> pending = new ArrayList<>();
                for (Container container : containers) {
                    var inspect = client.inspectContainerCmd(container.getId()).exec();
                    var state = inspect.getState();
                    String status = state == null ? "unknown" : stringValue(state.getStatus(), "unknown");
                    boolean ready = switch (condition) {
                        case "service_healthy" -> state != null && isRunning(state)
                                && state.getHealth() != null && "healthy".equalsIgnoreCase(state.getHealth().getStatus());
                        case "service_completed_successfully" -> state != null && !Boolean.TRUE.equals(state.getRunning())
                                && Integer.valueOf(0).equals(state.getExitCode());
                        default -> state != null && Boolean.TRUE.equals(state.getRunning())
                                && !Boolean.TRUE.equals(state.getPaused()) && !Boolean.TRUE.equals(state.getRestarting());
                    };
                    if (!ready) {
                        if ("service_healthy".equals(condition) && state != null && isRunning(state)
                                && state.getHealth() == null) {
                            String message = "Dependency '" + dependencyName + "' has no healthcheck configured.";
                            if (required) throw new IllegalStateException(message);
                            LOGGER.warning("Optional " + message + " Continuing service '" + service.name() + "'.");
                            pending.clear();
                            break;
                        }
                        if ("service_completed_successfully".equals(condition) && state != null
                                && !Boolean.TRUE.equals(state.getRunning()) && state.getExitCode() != null
                                && state.getExitCode() != 0) {
                            String message = "Dependency '" + dependencyName + "' exited with code " + state.getExitCode() + ".";
                            if (required) throw new IllegalStateException(message);
                            LOGGER.warning("Optional " + message + " Continuing service '" + service.name() + "'.");
                            pending.clear();
                            break;
                        }
                        if ("exited".equalsIgnoreCase(status) || "dead".equalsIgnoreCase(status) || "unhealthy".equalsIgnoreCase(
                                state == null || state.getHealth() == null ? null : state.getHealth().getStatus())) {
                            String message = "Dependency '" + dependencyName + "' entered state '" + status + "' before becoming ready.";
                            if (required) throw new IllegalStateException(message);
                            LOGGER.warning("Optional " + message + " Continuing service '" + service.name() + "'.");
                            pending.clear();
                            break;
                        }
                        pending.add(container.getId());
                    }
                }
                if (pending.isEmpty()) break;
                if (System.nanoTime() >= deadline) {
                    String message = "Timed out waiting for dependency '" + dependencyName + "' to satisfy " + condition + ".";
                    if (required) throw new java.util.concurrent.CompletionException(new java.util.concurrent.TimeoutException(message));
                    LOGGER.warning("Optional " + message + " Continuing service '" + service.name() + "'.");
                    break;
                }
                try { Thread.sleep(200); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("Interrupted while waiting for Compose dependencies.");
                }
            }
        }
    }

    private static boolean isRunning(com.github.dockerjava.api.command.InspectContainerResponse.ContainerState state) {
        return Boolean.TRUE.equals(state.getRunning()) && !Boolean.TRUE.equals(state.getPaused())
                && !Boolean.TRUE.equals(state.getRestarting());
    }

    private static void waitUntilNotRunning(DockerClient client, String containerId) {
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                var state = client.inspectContainerCmd(containerId).exec().getState();
                if (state == null || !Boolean.TRUE.equals(state.getRunning())) return;
            } catch (NotFoundException ignored) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("Interrupted while waiting for Docker to report the stopped container.");
            }
        }
        throw new java.util.concurrent.CompletionException(
                new java.util.concurrent.TimeoutException("Container '" + containerId
                        + "' was still running after waiting 5 seconds for Docker to report its stopped state."));
    }

    private static String dependencyCondition(Object rawDependencies, String name) {
        if (rawDependencies instanceof Map<?, ?> map && map.get(name) instanceof Map<?, ?> options)
            return stringValue(options.get("condition"), "service_started");
        return "service_started";
    }

    private static List<com.github.dockerjava.api.model.Bind> serviceBinds(ComposeProject project, String projectName, ServiceDefinition service) {
        Object raw = service.configuration().get("volumes");
        List<com.github.dockerjava.api.model.Bind> binds = new ArrayList<>();
        if (raw instanceof List<?> volumes) for (Object volume : volumes) {
            if (volume instanceof Map<?, ?> map) {
                Object target = map.get("target"), source = map.get("source");
                if (target == null) continue;
                String sourceName = source == null ? "" : String.valueOf(source);
                Object type = map.get("type");
                boolean bind = "bind".equals(String.valueOf(type)) || Path.of(sourceName.isBlank() ? "." : sourceName).isAbsolute()
                        || sourceName.startsWith(".") || sourceName.startsWith("~");
                String resolved = bind ? project.sourceFile().getParent().resolve(sourceName).normalize().toString()
                        : sourceName.isBlank() ? "" : engineVolumeName(projectName, project, sourceName);
                binds.add(new com.github.dockerjava.api.model.Bind(resolved, new com.github.dockerjava.api.model.Volume(String.valueOf(target)),
                        "ro".equalsIgnoreCase(String.valueOf(map.get("read_only"))) || Boolean.TRUE.equals(map.get("read_only"))
                                ? com.github.dockerjava.api.model.AccessMode.ro : com.github.dockerjava.api.model.AccessMode.rw));
            } else {
                String[] parts = String.valueOf(volume).split(":", -1);
                if (parts.length < 2) continue;
                String source = parts[0], target = parts[1];
                boolean bind = Path.of(source.isBlank() ? "." : source).isAbsolute() || source.startsWith(".") || source.startsWith("~");
                String resolved = bind ? project.sourceFile().getParent().resolve(source).normalize().toString()
                        : source.isBlank() ? "" : engineVolumeName(projectName, project, source);
                boolean readOnly = parts.length > 2 && parts[2].toLowerCase(java.util.Locale.ROOT).contains("ro");
                binds.add(new com.github.dockerjava.api.model.Bind(resolved, new com.github.dockerjava.api.model.Volume(target),
                        readOnly ? com.github.dockerjava.api.model.AccessMode.ro : com.github.dockerjava.api.model.AccessMode.rw));
            }
        }
        addFileResourceBinds(project, service, "secrets", project.secretDefinitions(), binds);
        addFileResourceBinds(project, service, "configs", project.configDefinitions(), binds);
        return binds;
    }

    private static void addFileResourceBinds(ComposeProject project, ServiceDefinition service, String field,
                                             Map<String, Object> definitions,
                                             List<com.github.dockerjava.api.model.Bind> binds) {
        Object raw = service.configuration().get(field);
        if (!(raw instanceof List<?> resources)) return;
        for (Object resource : resources) {
            Map<?, ?> grant = resource instanceof Map<?, ?> map ? map : Map.of("source", resource);
            String sourceName = stringValue(grant.get("source"), null);
            if (sourceName == null || sourceName.isBlank())
                throw new IllegalArgumentException("services." + service.name() + "." + field + " entries must name a source.");
            Object rawDefinition = definitions.get(sourceName);
            if (!(rawDefinition instanceof Map<?, ?> definition))
                throw new IllegalArgumentException("services." + service.name() + "." + field + " references undefined resource '" + sourceName + "'.");
            if (Boolean.TRUE.equals(definition.get("external")) || definition.get("external") instanceof Map<?, ?>)
                throw new IllegalArgumentException("External Compose " + field + " are not supported by the Docker Engine bind-mount backend: " + sourceName);
            if (definition.get("environment") != null || definition.get("content") != null)
                throw new IllegalArgumentException("Compose " + field + " using environment or content sources are not supported by the Docker Engine bind-mount backend: " + sourceName);
            Object file = definition.get("file");
            if (file == null)
                throw new IllegalArgumentException("Compose " + field + " '" + sourceName + "' must define a file source for the Docker Engine bind-mount backend.");
            Path source = project.sourceFile().getParent().resolve(String.valueOf(file)).normalize().toAbsolutePath();
            if (!java.nio.file.Files.isRegularFile(source))
                throw new IllegalArgumentException("Compose " + field + " file for '" + sourceName + "' does not exist or is not a regular file: " + source);
            String target = stringValue(grant.get("target"), null);
            if (target == null || target.isBlank()) target = defaultResourceTarget(sourceName, service, field);
            else if (!isAbsoluteContainerPath(target)) {
                if ("secrets".equals(field)) {
                    if (stringValue(service.configuration().get("platform"), "linux")
                            .toLowerCase(java.util.Locale.ROOT).contains("windows"))
                        throw new IllegalArgumentException("Windows Compose secret targets must be absolute paths: " + target);
                    target = "/run/secrets/" + target;
                } else target = defaultResourceTarget(target, service, field);
            }
            binds.add(new com.github.dockerjava.api.model.Bind(source.toString(),
                    new com.github.dockerjava.api.model.Volume(target), com.github.dockerjava.api.model.AccessMode.ro));
        }
    }

    private static String defaultResourceTarget(String sourceName, ServiceDefinition service, String field) {
        String platform = stringValue(service.configuration().get("platform"), "linux");
        if (platform.toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            if ("secrets".equals(field))
                throw new IllegalArgumentException("Windows containers require an explicit target for Compose secrets: " + sourceName);
            return "C:\\" + sourceName;
        }
        return "secrets".equals(field) ? "/run/secrets/" + sourceName : "/" + sourceName;
    }

    private static boolean isAbsoluteContainerPath(String path) {
        return path.startsWith("/") || path.startsWith("\\\\")
                || path.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static Map<String, String> parseKeyValueOptions(List<String> values, String optionName) {
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) return result;
        for (String value : values) {
            int separator = value.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("A " + optionName + " must use KEY=VALUE form: " + value);
            result.put(value.substring(0, separator), value.substring(separator + 1));
        }
        return result;
    }

    private static com.github.dockerjava.api.model.Bind parseRunBind(String value) {
        int first = value.matches("^[A-Za-z]:[\\\\/].*") ? value.indexOf(':', 2) : value.indexOf(':');
        if (first <= 0 || first == value.length() - 1)
            throw new IllegalArgumentException("A run volume must use source:target[:mode] form: " + value);
        int second = value.indexOf(':', first + 1);
        String source = value.substring(0, first);
        String target = second < 0 ? value.substring(first + 1) : value.substring(first + 1, second);
        String mode = second < 0 ? "rw" : value.substring(second + 1);
        if (source.isBlank() || target.isBlank()) throw new IllegalArgumentException("A run volume must specify source and target: " + value);
        return new com.github.dockerjava.api.model.Bind(source, new com.github.dockerjava.api.model.Volume(target),
                mode.toLowerCase(java.util.Locale.ROOT).contains("ro")
                        ? com.github.dockerjava.api.model.AccessMode.ro : com.github.dockerjava.api.model.AccessMode.rw);
    }

    private static List<Container> serviceArchiveContainers(DockerClient client, String projectName, String service, boolean includeOneOff) {
        return projectContainers(client, projectName, List.of(service), true).stream()
                .filter(container -> includeOneOff || !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                .sorted(containerOrder()).toList();
    }

    private static Container archiveContainer(DockerClient client, String projectName, String service, Integer index, boolean includeOneOff) {
        List<Container> containers = serviceArchiveContainers(client, projectName, service, includeOneOff);
        if (index != null) {
            if (index <= 0) throw new IllegalArgumentException("Container indexes are 1-based.");
            return containers.stream().filter(c -> Integer.toString(index).equals(label(c, ComposeConstants.CONTAINER_NUMBER_LABEL)))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No container at index " + index + " for service '" + service + "'."));
        }
        return containers.stream().findFirst().orElseThrow(() -> new IllegalStateException("No container found for service '" + service + "'."));
    }

    private static void rejectSymlinkTree(Path source) throws java.io.IOException {
        if (Files.isSymbolicLink(source)) throw new java.io.IOException("Copying symbolic links is not supported: " + source);
        if (Files.isDirectory(source)) try (var paths = Files.walk(source)) {
            Path link = paths.filter(Files::isSymbolicLink).findFirst().orElse(null);
            if (link != null) throw new java.io.IOException("Copying symbolic links is not supported: " + link);
        }
    }

    static Path resolveCopySource(Path source, boolean followLink) throws java.io.IOException {
        return followLink && Files.isSymbolicLink(source) ? source.toRealPath() : source;
    }

    static long appendTarEntry(TarArchiveOutputStream tar, Path path, String archiveName) throws java.io.IOException {
        TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), archiveName + (Files.isDirectory(path) ? "/" : ""));
        tar.putArchiveEntry(entry);
        long bytes = 0;
        if (Files.isDirectory(path)) {
            tar.closeArchiveEntry();
            try (var children = Files.list(path)) {
                for (Path child : children.sorted().toList())
                    bytes = Math.addExact(bytes, appendTarEntry(tar, child, archiveName + "/" + child.getFileName()));
            }
        } else {
            bytes = Files.size(path);
            Files.copy(path, tar);
            tar.closeArchiveEntry();
        }
        return bytes;
    }

    static CopyExtraction extractTar(InputStream archive, Path destination) throws java.io.IOException {
        Path root = destination.toAbsolutePath().normalize();
        long bytes = 0;
        boolean directory = false;
        boolean first = true;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (first) { directory = entry.isDirectory(); first = false; }
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) throw new java.io.IOException("Docker archive contains a path outside destination: " + entry.getName());
                if (entry.isSymbolicLink() || !(entry.isDirectory() || entry.isFile() || entry.isLink()))
                    throw new java.io.IOException("Docker archive contains unsupported entry type: " + entry.getName());
                ensureNoSymlinkPath(root, target);
                if (entry.isDirectory()) Files.createDirectories(target);
                else if (entry.isLink()) {
                    String linkName = entry.getLinkName();
                    if (linkName == null || linkName.isBlank())
                        throw new java.io.IOException("Docker archive contains a hard link without a target: " + entry.getName());
                    Path linkTarget = root.resolve(linkName).normalize();
                    if (!linkTarget.startsWith(root))
                        throw new java.io.IOException("Docker archive contains a hard link outside destination: " + linkName);
                    ensureNoSymlinkPath(root, linkTarget);
                    if (!Files.isRegularFile(linkTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        throw new java.io.IOException("Docker archive contains a hard link before its target: " + linkName);
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    long linkBytes = Files.size(linkTarget);
                    Files.copy(linkTarget, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    bytes = Math.addExact(bytes, linkBytes);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (var output = Files.newOutputStream(target)) { bytes = Math.addExact(bytes, tar.transferTo(output)); }
                }
            }
        }
        return new CopyExtraction(bytes, directory);
    }

    private static void ensureNoSymlinkPath(Path root, Path target) throws java.io.IOException {
        Path current = root;
        if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current))
            throw new java.io.IOException("Copy destination traverses a symbolic link: " + current);
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current))
                throw new java.io.IOException("Copy destination traverses a symbolic link: " + current);
        }
    }

    static void ensureNoSymlinkAncestors(Path path) throws java.io.IOException {
        Path current = path.getRoot();
        if (current == null) throw new java.io.IOException("Copy destination must be an absolute path: " + path);
        for (Path segment : path) {
            current = current.resolve(segment);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current))
                throw new java.io.IOException("Copy destination traverses a symbolic link: " + current);
        }
    }

    record CopyExtraction(long bytes, boolean directory) { }
    private record CopyPath(boolean container, String service, String path) {
        private static final Pattern SERVICE_PATH = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9_.-]*):(/.*)$");
        static CopyPath parse(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Copy paths must not be empty.");
            if (Path.of(value).isAbsolute()) return new CopyPath(false, null, value);
            var matcher = SERVICE_PATH.matcher(value);
            return matcher.matches() ? new CopyPath(true, matcher.group(1), matcher.group(2)) : new CopyPath(false, null, value);
        }
    }

    private static List<com.github.dockerjava.api.model.PortBinding> servicePorts(ServiceDefinition service) {
        Object raw = service.configuration().get("ports");
        if (!(raw instanceof List<?> ports)) return List.of();
        List<com.github.dockerjava.api.model.PortBinding> result = new ArrayList<>();
        for (Object port : ports) {
            try {
                if (port instanceof Map<?, ?> map) {
                    Object target = map.get("target"), published = map.get("published"), protocol = map.get("protocol"), hostIp = map.get("host_ip");
                    if (target == null) continue;
                    String exposedPort = String.valueOf(target) + (protocol == null ? "/tcp" : "/" + protocol);
                    if (published == null || String.valueOf(published).isBlank()) {
                        Ports.Binding binding = hostIp == null || String.valueOf(hostIp).isBlank()
                                ? Ports.Binding.empty() : Ports.Binding.bindIp(String.valueOf(hostIp));
                        result.add(new com.github.dockerjava.api.model.PortBinding(binding,
                                com.github.dockerjava.api.model.ExposedPort.parse(exposedPort)));
                        continue;
                    }
                    String binding = (hostIp == null ? "" : hostIp + ":") + published + ":" + target
                            + (protocol == null ? "" : "/" + protocol);
                    result.add(com.github.dockerjava.api.model.PortBinding.parse(binding));
                } else {
                    String value = String.valueOf(port);
                    if (value.indexOf(':') < 0) {
                        result.add(new com.github.dockerjava.api.model.PortBinding(Ports.Binding.empty(),
                                com.github.dockerjava.api.model.ExposedPort.parse(value)));
                    } else result.add(com.github.dockerjava.api.model.PortBinding.parse(value));
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported ports entry for service '" + service.name() + "': " + port, exception);
            }
        }
        return result;
    }

    private static List<com.github.dockerjava.api.model.ExposedPort> serviceExposedPorts(ServiceDefinition service) {
        java.util.LinkedHashSet<com.github.dockerjava.api.model.ExposedPort> result = new java.util.LinkedHashSet<>();
        for (var binding : servicePorts(service)) result.add(binding.getExposedPort());
        Object expose = service.configuration().get("expose");
        if (expose instanceof List<?> list) for (Object value : list) {
            String port = String.valueOf(value);
            if (port.indexOf('/') < 0) port += "/tcp";
            result.add(com.github.dockerjava.api.model.ExposedPort.parse(port));
        }
        return List.copyOf(result);
    }

    private static List<String> asStringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(value));
    }

    private static String containerName(String projectName, ServiceDefinition service, int replicas, int index) {
        if (replicas == 1 && service.configuration().get("container_name") != null)
            return String.valueOf(service.configuration().get("container_name"));
        return projectName + "-" + service.name() + "-" + index;
    }

    private static String configHash(ServiceDefinition service) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(service.configuration().toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static com.github.dockerjava.api.model.RestartPolicy restartPolicy(String value) {
        String[] parts = value.split(":", 2);
        String name = parts[0].toLowerCase(java.util.Locale.ROOT);
        int retries = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return switch (name) {
            case "no" -> com.github.dockerjava.api.model.RestartPolicy.noRestart();
            case "always" -> com.github.dockerjava.api.model.RestartPolicy.alwaysRestart();
            case "unless-stopped" -> com.github.dockerjava.api.model.RestartPolicy.unlessStoppedRestart();
            case "on-failure" -> com.github.dockerjava.api.model.RestartPolicy.onFailureRestart(retries);
            default -> throw new IllegalArgumentException("Unsupported restart policy: " + value);
        };
    }

    private static com.github.dockerjava.api.model.RestartPolicy effectiveRestartPolicy(Map<String, Object> config) {
        Object deployValue = config.get("deploy");
        Object policyValue = deployValue instanceof Map<?, ?> deploy ? deploy.get("restart_policy") : null;
        if (!(policyValue instanceof Map<?, ?> policy)) {
            Object restart = config.get("restart");
            return restart == null ? null : restartPolicy(String.valueOf(restart));
        }
        String condition = stringValue(policy.get("condition"), "any").toLowerCase(java.util.Locale.ROOT);
        return switch (condition) {
            case "none" -> com.github.dockerjava.api.model.RestartPolicy.noRestart();
            case "any" -> com.github.dockerjava.api.model.RestartPolicy.alwaysRestart();
            case "on-failure" -> {
                Object attempts = policy.get("max_attempts");
                long retries = attempts == null ? 0 : parseLong(attempts, "deploy.restart_policy.max_attempts");
                if (retries < 0) throw new IllegalArgumentException("deploy.restart_policy.max_attempts must be non-negative.");
                yield com.github.dockerjava.api.model.RestartPolicy.onFailureRestart(Math.toIntExact(retries));
            }
            default -> throw new IllegalArgumentException("Unsupported deploy.restart_policy.condition: " + condition);
        };
    }

    private static void awaitServiceRunning(DockerClient client, String projectName, ServiceDefinition service,
                                           Integer timeoutSeconds) {
        List<Container> containers = projectContainers(client, projectName, List.of(service.name()), true).stream()
                .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                .toList();
        if (containers.isEmpty()) throw new IllegalStateException("Service '" + service.name() + "' has no containers to wait for.");
        var healthcheck = serviceHealthcheck(service);
        boolean healthyRequired = healthcheck != null && healthcheck.getTest() != null
                && !healthcheck.getTest().isEmpty() && !"NONE".equalsIgnoreCase(healthcheck.getTest().getFirst());
        long deadline = timeoutSeconds == null ? Long.MAX_VALUE
                : System.nanoTime() + TimeUnit.SECONDS.toNanos(positiveTimeout(timeoutSeconds));
        while (true) {
            boolean ready = true;
            for (Container container : containers) {
                var state = client.inspectContainerCmd(container.getId()).exec().getState();
                if (state == null || !isRunning(state)) {
                    if (state != null && ("exited".equalsIgnoreCase(state.getStatus()) || "dead".equalsIgnoreCase(state.getStatus())))
                        throw new IllegalStateException("Service '" + service.name() + "' container exited before becoming ready.");
                    ready = false;
                } else if (healthyRequired && (state.getHealth() == null
                        || !"healthy".equalsIgnoreCase(state.getHealth().getStatus()))) {
                    if (state.getHealth() != null && "unhealthy".equalsIgnoreCase(state.getHealth().getStatus()))
                        throw new IllegalStateException("Service '" + service.name() + "' became unhealthy before it was ready.");
                    ready = false;
                }
            }
            if (ready) return;
            if (System.nanoTime() >= deadline) throw new java.util.concurrent.CompletionException(
                    new java.util.concurrent.TimeoutException("Timed out waiting for service '" + service.name() + "' to become ready."));
            try { Thread.sleep(200); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("Interrupted while waiting for a service to become ready.");
            }
        }
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Map<String, String> stringMap(Object source) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String name = String.valueOf(key);
                if (value != null) result.put(name, String.valueOf(value));
                else if (System.getenv(name) != null) result.put(name, System.getenv(name));
            });
        } else if (source instanceof List<?> list) {
            for (Object item : list) {
                String pair = String.valueOf(item);
                int separator = pair.indexOf('=');
                String name = separator < 0 ? pair : pair.substring(0, separator);
                String value = separator < 0 ? System.getenv(name) : pair.substring(separator + 1);
                if (value != null) result.put(name, value);
            }
        }
        return result;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static long parseByteSize(String value) {
        java.util.regex.Matcher matcher = Pattern.compile("(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*(b|k|kb|kib|m|mb|mib|g|gb|gib|t|tb|tib|p|pb|pib)?\\s*$").matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid byte size: " + value);
        long multiplier = switch (matcher.group(2) == null ? "b" : matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "b" -> 1L;
            case "k", "kb", "kib" -> 1024L;
            case "m", "mb", "mib" -> 1024L * 1024;
            case "g", "gb", "gib" -> 1024L * 1024 * 1024;
            case "t", "tb", "tib" -> 1024L * 1024 * 1024 * 1024;
            case "p", "pb", "pib" -> 1024L * 1024 * 1024 * 1024 * 1024;
            default -> throw new IllegalArgumentException("Invalid byte size: " + value);
        };
        try {
            return new BigDecimal(matcher.group(1)).multiply(BigDecimal.valueOf(multiplier)).toBigInteger().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Byte size is outside the supported range: " + value, exception);
        }
    }

    private static int positiveTimeout(Integer timeout) {
        return timeout == null || timeout < 0 ? DEFAULT_STOP_TIMEOUT_SECONDS : timeout;
    }

    private CompletableFuture<Void> execute(ComposeProjectContext context, DockerOperation operation) {
        return supply(() -> {
            try (DockerClient client = createDockerClient(context)) {
                operation.accept(client);
                return null;
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    private static <T> CompletableFuture<T> supply(Supplier<T> operation) {
        OperationFuture<T> result = new OperationFuture<>();
        Future<?> task = OPERATION_EXECUTOR.submit(() -> {
            CURRENT_OPERATION.set(result);
            try {
                result.complete(operation.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                CURRENT_OPERATION.remove();
                result.releaseClients();
            }
        });
        result.attach(task);
        OperationFuture<?> parent = CURRENT_OPERATION.get();
        if (parent != null) parent.registerChild(result);
        return result;
    }

    private static <T> T await(CompletableFuture<T> operation) {
        try {
            return operation.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Compose operation was interrupted.");
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable failure = exception.getCause();
            if (failure instanceof RuntimeException runtimeException) throw runtimeException;
            if (failure instanceof Error error) throw error;
            throw new java.util.concurrent.CompletionException(failure);
        }
    }

    private static DockerClient createDockerClient(ComposeProjectContext context) {
        OperationFuture<?> operation = CURRENT_OPERATION.get();
        if (operation != null && operation.isCancelled())
            throw new java.util.concurrent.CancellationException("Compose operation was cancelled.");
        DockerClient client = DockerClientFactory.create(context);
        if (operation != null) operation.register(client);
        return client;
    }

    private static final class OperationFuture<T> extends CompletableFuture<T> {
        private final Set<DockerClient> clients = ConcurrentHashMap.newKeySet();
        private final Set<OperationFuture<?>> children = ConcurrentHashMap.newKeySet();
        private volatile Future<?> task;

        private void attach(Future<?> value) {
            task = value;
            if (isCancelled()) value.cancel(true);
        }

        private void register(DockerClient client) {
            clients.add(client);
            if (isCancelled()) closeClients();
        }

        private void registerChild(OperationFuture<?> child) {
            children.add(child);
            child.whenComplete((value, failure) -> children.remove(child));
            if (isCancelled()) child.cancel(true);
        }

        private void releaseClients() { clients.clear(); }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (!cancelled) return false;
            Future<?> activeTask = task;
            if (activeTask != null) activeTask.cancel(mayInterruptIfRunning);
            for (OperationFuture<?> child : children) child.cancel(mayInterruptIfRunning);
            if (mayInterruptIfRunning) closeClients();
            return true;
        }

        private void closeClients() {
            for (DockerClient client : clients) {
                try { client.close(); } catch (Exception ignored) { }
            }
            clients.clear();
        }
    }

    private static Integer parseTail(String tail) {
        if (tail == null || tail.isBlank() || "all".equalsIgnoreCase(tail)) return null;
        try {
            int value = Integer.parseInt(tail);
            if (value < 0) throw new IllegalArgumentException("tail must be 'all' or a non-negative number");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("tail must be 'all' or a non-negative number", exception);
        }
    }

    private static Integer parseLogTimestamp(String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        String input = value.trim();
        try {
            if (input.matches("\\d+")) return Integer.valueOf(input);
            Instant timestamp;
            try {
                timestamp = Instant.parse(input);
            } catch (java.time.format.DateTimeParseException ignored) {
                try {
                    timestamp = OffsetDateTime.parse(input).toInstant();
                } catch (java.time.format.DateTimeParseException ignoredOffset) {
                    try {
                        timestamp = LocalDate.parse(input).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                    } catch (java.time.format.DateTimeParseException ignoredDate) {
                        long durationNanos = parseDurationNanos(input);
                        if (durationNanos < 0) throw new IllegalArgumentException("Log timestamp duration must not be negative: " + value);
                        timestamp = now.minusNanos(durationNanos);
                    }
                }
            }
            return Math.toIntExact(timestamp.getEpochSecond());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Log timestamp is outside the supported range: " + value, exception);
        }
    }

    private static final class LogCallback extends Adapter<Frame> {
        private final String service;
        private final ILogConsumer consumer;
        private final boolean noColor;
        private final Map<StreamType, StringBuilder> pending = new EnumMap<>(StreamType.class);

        private LogCallback(String service, ILogConsumer consumer, boolean noColor) {
            this.service = service;
            this.consumer = consumer;
            this.noColor = noColor;
        }

        @Override
        public synchronized void onNext(Frame frame) {
            byte[] payload = frame.getPayload();
            if (payload == null || payload.length == 0) return;
            StreamType streamType = frame.getStreamType() == StreamType.STDERR ? StreamType.STDERR : StreamType.STDOUT;
            StringBuilder buffer = pending.computeIfAbsent(streamType, ignored -> new StringBuilder());
            buffer.append(new String(payload, StandardCharsets.UTF_8));
            int newline;
            while ((newline = buffer.indexOf("\n")) >= 0) {
                String line = buffer.substring(0, newline);
                buffer.delete(0, newline + 1);
                emit(stripCarriageReturn(line), streamType);
            }
        }

        @Override
        public synchronized void onComplete() {
            pending.forEach((streamType, buffer) -> {
                if (!buffer.isEmpty()) emit(buffer.toString(), streamType);
            });
            pending.clear();
            consumer.onLogComplete(service);
            super.onComplete();
        }

        private void emit(String line, StreamType streamType) {
            String message = noColor ? ANSI_ESCAPE.matcher(line).replaceAll("") : line;
            consumer.onLog(service, message, streamType == StreamType.STDERR);
        }

        private static String stripCarriageReturn(String line) {
            return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        }
    }

    private Flow.Publisher<ComposeEvent> eventsPublisher(ComposeProjectContext context, ComposeEventsOptions options) {
        return subscriber -> {
            if (subscriber == null) throw new NullPointerException("subscriber");
            EventSubscription subscription = new EventSubscription(subscriber, context, options);
            subscriber.onSubscribe(subscription);
        };
    }

    private final class EventSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super ComposeEvent> subscriber;
        private final ComposeProjectContext context;
        private final ComposeEventsOptions options;
        private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final Object lock = new Object();
        private final AtomicBoolean started = new AtomicBoolean();
        private volatile DockerClient client;
        private volatile ResultCallback.Adapter<Event> callback;
        private long demand;
        private boolean complete;
        private boolean cancelled;
        private Throwable failure;

        private EventSubscription(Flow.Subscriber<? super ComposeEvent> subscriber,
                                  ComposeProjectContext context, ComposeEventsOptions options) {
            this.subscriber = subscriber;
            this.context = java.util.Objects.requireNonNull(context, "context");
            this.options = options == null ? ComposeEventsOptions.builder().build() : options;
        }

        @Override public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("Flow.Subscription.request requires a positive count."));
                return;
            }
            synchronized (lock) {
                if (cancelled || complete) return;
                demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
                if (options.services() != null && options.services().isEmpty()) {
                    complete = true;
                    lock.notifyAll();
                    if (started.compareAndSet(false, true)) CompletableFuture.runAsync(this::drainEvents);
                    return;
                }
                lock.notifyAll();
            }
            if (started.compareAndSet(false, true)) CompletableFuture.runAsync(this::subscribeToDocker);
        }

        @Override public void cancel() {
            synchronized (lock) {
                if (cancelled) return;
                cancelled = true;
                lock.notifyAll();
            }
            closeResources();
        }

        private void subscribeToDocker() {
            try {
                DockerClient activeClient = createDockerClient(context);
                client = activeClient;
                if (isCancelled()) { closeResources(); return; }
                ProjectResources resources = projectResources(activeClient, context);
                ResultCallback.Adapter<Event> activeCallback = new Adapter<>() {
                    @Override public void onStart(java.io.Closeable stream) {
                        super.onStart(stream);
                        try {
                            if (options.onSubscribed() != null) options.onSubscribed().run();
                        } catch (Throwable exception) {
                            fail(exception);
                        }
                    }

                    @Override public void onNext(Event event) {
                        if (event != null && belongsToProject(event, resources) && selectedService(event, resources))
                            events.offer(event);
                        synchronized (lock) { lock.notifyAll(); }
                    }

                    @Override public void onError(Throwable throwable) { fail(throwable); }

                    @Override public void onComplete() {
                        synchronized (lock) { complete = true; lock.notifyAll(); }
                    }
                };
                callback = activeCallback;
                activeClient.eventsCmd()
                        .withEventTypeFilter(EventType.CONTAINER, EventType.NETWORK, EventType.VOLUME)
                        .exec(activeCallback);
                CompletableFuture.runAsync(this::drainEvents);
            } catch (Throwable exception) {
                fail(exception);
                CompletableFuture.runAsync(this::drainEvents);
            }
        }

        private void drainEvents() {
            try {
                while (true) {
                    Event event;
                    synchronized (lock) {
                        while (!cancelled && (demand == 0 || events.isEmpty()) && !complete) lock.wait();
                        if (cancelled) return;
                        if (demand == 0 || events.isEmpty()) {
                            if (complete && events.isEmpty()) break;
                            continue;
                        }
                        event = events.poll();
                        if (demand != Long.MAX_VALUE) demand--;
                    }
                    subscriber.onNext(ComposeService.toComposeEvent(event));
                }
                if (failure == null) subscriber.onComplete(); else subscriber.onError(failure);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!isCancelled()) subscriber.onError(exception);
            } catch (Throwable exception) {
                cancel();
                try { subscriber.onError(exception); } catch (Throwable ignored) { }
            } finally {
                closeResources();
            }
        }

        private boolean selectedService(Event event, ProjectResources resources) {
            List<String> selected = options.services();
            Map<String, String> attributes = event.getActor() == null ? null : event.getActor().getAttributes();
            String service = attributes == null ? null : attributes.get("com.docker.compose.service");
            if (selected != null) {
                java.util.Set<String> allowed = resources.services() == null ? java.util.Set.copyOf(selected) : resources.services();
                return service != null && allowed.contains(service);
            }
            return service == null || resources.services() == null || resources.services().contains(service);
        }

        private boolean belongsToProject(Event event, ProjectResources resources) {
            Map<String, String> attributes = event.getActor() == null ? null : event.getActor().getAttributes();
            String owner = attributes == null ? null : attributes.get("com.docker.compose.project");
            if (owner != null) return context.projectName().equals(owner);
            String type = event.getType() == null ? "" : event.getType().getValue();
            if ("network".equalsIgnoreCase(type)) {
                String name = attributes == null ? null : attributes.get("name");
                if (name == null && event.getActor() != null) name = event.getActor().getId();
                return name != null && resources.networks().contains(name);
            }
            if ("volume".equalsIgnoreCase(type)) {
                String name = event.getActor() == null ? null : event.getActor().getId();
                if (name == null && attributes != null) name = attributes.get("name");
                return name != null && resources.volumes().contains(name);
            }
            return false;
        }

        private ProjectResources projectResources(DockerClient activeClient, ComposeProjectContext context) {
            java.util.Set<String> networks = new java.util.HashSet<>();
            java.util.Set<String> volumes = new java.util.HashSet<>();
            java.util.Set<String> services = null;
            if (loader.hasComposeFile(context.workingDirectory(), context.composeFileName())
                    || context.profiles() != null && !context.profiles().isEmpty()) {
                ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
                services = selectConfigurationServices(project, context.profiles(), options.services()).stream()
                        .map(ServiceDefinition::name).collect(java.util.stream.Collectors.toSet());
                List<String> declaredNetworks = project.networks().isEmpty() ? List.of("default") : project.networks();
                declaredNetworks.forEach(name -> networks.add(engineNetworkName(context.projectName(), project, name)));
                project.volumes().forEach(name -> volumes.add(engineVolumeName(context.projectName(), project, name)));
            }
            String projectLabel = "com.docker.compose.project=" + context.projectName();
            activeClient.listNetworksCmd().withFilter("label", List.of(projectLabel)).exec()
                    .stream().map(com.github.dockerjava.api.model.Network::getName).filter(java.util.Objects::nonNull).forEach(networks::add);
            var volumeResponse = activeClient.listVolumesCmd().withFilter("label", List.of(projectLabel)).exec();
            if (volumeResponse.getVolumes() != null) volumeResponse.getVolumes().stream()
                    .map(com.github.dockerjava.api.command.InspectVolumeResponse::getName)
                    .filter(java.util.Objects::nonNull).forEach(volumes::add);
            return new ProjectResources(networks, volumes, services);
        }

        private void fail(Throwable throwable) {
            synchronized (lock) {
                if (cancelled || complete) return;
                failure = throwable;
                complete = true;
                lock.notifyAll();
            }
        }

        private boolean isCancelled() { synchronized (lock) { return cancelled; } }

        private void closeResources() {
            ResultCallback.Adapter<Event> activeCallback = callback;
            if (activeCallback != null) try { activeCallback.close(); } catch (Exception ignored) { }
            DockerClient activeClient = client;
            if (activeClient != null) try { activeClient.close(); } catch (Exception ignored) { }
        }
    }

    static ComposeEvent toComposeEvent(Event event) {
        Map<String, String> attributes = event.getActor() == null || event.getActor().getAttributes() == null
                ? null : Map.copyOf(event.getActor().getAttributes());
        String type = event.getType() == null ? "" : event.getType().getValue();
        String id = event.getActor() != null && event.getActor().getId() != null
                ? event.getActor().getId() : event.getId() == null ? "" : event.getId();
        String service = attributes == null ? null : attributes.get("com.docker.compose.service");
        String action = event.getAction() != null ? event.getAction()
                : event.getStatus() == null ? "" : event.getStatus();
        long epochNanos = event.getTimeNano() == null ? 0 : event.getTimeNano();
        Instant timestamp = epochNanos > 0
                ? Instant.ofEpochSecond(Math.floorDiv(epochNanos, 1_000_000_000L),
                Math.floorMod(epochNanos, 1_000_000_000L))
                : Instant.ofEpochSecond(event.getTime() == null ? 0 : event.getTime());
        return new ComposeEvent(timestamp, type, action, id, service,
                "container".equalsIgnoreCase(type) ? id : null, attributes);
    }

    static java.util.Set<String> buildExtraHosts(Object configured) {
        java.util.Set<String> hosts = new java.util.LinkedHashSet<>();
        if (configured instanceof Map<?, ?> mapping) {
            mapping.forEach((host, address) -> hosts.add(String.valueOf(host) + ":"
                    + (address == null ? "" : address)));
        } else if (configured instanceof List<?> list) {
            list.stream().map(String::valueOf).forEach(hosts::add);
        }
        return hosts;
    }

    private record ProjectResources(java.util.Set<String> networks, java.util.Set<String> volumes,
                                    java.util.Set<String> services) { }

    @FunctionalInterface
    private interface DockerOperation {
        void accept(DockerClient client);
    }

    @Override
    public CompletableFuture<Void> buildAsync(ComposeProjectContext context, ComposeBuildOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> selected = selectConfigurationServices(project, context.profiles(),
                    options == null ? null : options.services());
            selected.forEach(ComposeService::validateBuildLabels);
            try (DockerClient client = createDockerClient(context)) {
                for (ServiceDefinition service : selected) {
                    Object buildValue = service.build();
                    if (buildValue == null || Boolean.FALSE.equals(buildValue)) continue;
                    Map<String, Object> build = buildValue instanceof Map<?, ?> map
                            ? stringKeyMap(map) : Map.of("context", buildValue);
                    Path projectDirectory = project.sourceFile().getParent();
                    Path buildContext = projectDirectory.resolve(stringValue(build.get("context"), ".")).normalize();
                    if (!Files.isDirectory(buildContext))
                        throw new IllegalArgumentException("Build context for service '" + service.name() + "' does not exist: " + buildContext);
                    String dockerfile = stringValue(build.get("dockerfile"), "Dockerfile");
                    Path dockerfilePath = buildContext.resolve(dockerfile).normalize();
                    if (!Files.isRegularFile(dockerfilePath))
                        throw new IllegalArgumentException("Dockerfile for service '" + service.name() + "' does not exist: " + dockerfilePath);

                    String image = service.image() == null || service.image().isBlank()
                            ? context.projectName() + "-" + service.name() : service.image();
                    try (DockerBuildContextArchive archive = DockerBuildContextArchive.create(buildContext, dockerfilePath)) {
                        var command = client.buildImageCmd()
                                .withTarInputStream(archive.stream())
                                .withDockerfilePath(archive.dockerfilePath())
                                .withTags(buildTags(image, build.get("tags")))
                                .withNoCache(options != null && options.noCache() || Boolean.TRUE.equals(build.get("no_cache")))
                                .withPull(!context.offline() && (options != null && options.pull() || Boolean.TRUE.equals(build.get("pull"))))
                                .withQuiet(options != null && options.quiet())
                                .withTarget(options != null && options.target() != null
                                        ? options.target() : stringValue(build.get("target"), null));
                        String platform = options != null && options.platform() != null
                                ? options.platform() : buildPlatform(service, build);
                        if (platform != null) command.withPlatform(platform);
                        Map<String, String> buildArgs = new LinkedHashMap<>(stringMap(build.get("args")));
                        if (options != null && options.buildArgs() != null) buildArgs.putAll(options.buildArgs());
                        buildArgs.forEach(command::withBuildArg);
                        Map<String, String> labels = new LinkedHashMap<>(stringMap(build.get("labels")));
                        if (options != null && options.labels() != null) labels.putAll(options.labels());
                        validateComposeLabels(labels);
                        if (!labels.isEmpty()) command.withLabels(labels);
                        if (build.get("network") != null) command.withNetworkMode(String.valueOf(build.get("network")));
                        String memory = options != null && options.memory() != null ? options.memory() : stringValue(build.get("memory"), null);
                        if (memory != null) command.withMemory(parseByteSize(memory));
                        if (build.get("shm_size") != null) command.withShmsize(parseByteSize(String.valueOf(build.get("shm_size"))));
                        java.util.Set<String> extraHosts = buildExtraHosts(build.get("extra_hosts"));
                        if (!extraHosts.isEmpty()) command.withExtraHosts(extraHosts);
                        if (build.get("cache_from") instanceof List<?> cacheFrom && !cacheFrom.isEmpty())
                            command.withCacheFrom(cacheFrom.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()));
                        if (context.registryAuth() != null) {
                            AuthConfigurations authConfigurations = new AuthConfigurations();
                            authConfigurations.addConfig(registryAuth(context.registryAuth()));
                            command.withBuildAuthConfigs(authConfigurations);
                        }

                        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
                        Adapter<BuildResponseItem> callback = command.exec(new Adapter<>() {
                            @Override public void onNext(BuildResponseItem item) {
                                String error = item.getError();
                                if (error == null && item.getErrorDetail() != null) error = item.getErrorDetail().getMessage();
                                if (error != null && !error.isBlank()) failure.compareAndSet(null, error);
                                if (options != null && options.logConsumer() != null) {
                                    String line = item.getStream() != null ? item.getStream() : item.getStatus();
                                    if (line != null && !line.isBlank()) options.logConsumer().onStatus(service.name(), line.stripTrailing());
                                }
                            }
                        });
                        await(callback);
                        if (failure.get() != null)
                            throw new IllegalStateException("Build of service '" + service.name() + "' failed: " + failure.get());
                    }
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not prepare or close a Docker build context.", exception);
            }
            return null;
        });
    }

    private static String buildPlatform(ServiceDefinition service, Map<String, Object> build) {
        List<String> platforms = asStringList(build.get("platforms"));
        if (platforms.size() > 1) throw new UnsupportedOperationException("Service '" + service.name()
                + "' configures multiple build platforms. Docker Engine builds currently support one platform per request.");
        if (!platforms.isEmpty()) return platforms.getFirst();
        return stringValue(build.get("platform"), stringValue(service.configuration().get("platform"), null));
    }

    static java.util.Set<String> buildTags(String image, Object configuredTags) {
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
        tags.add(image);
        tags.addAll(asStringList(configuredTags));
        return tags;
    }

    @Override
    public CompletableFuture<Void> upAsync(ComposeProjectContext context, ComposeUpOptions options) {
        ComposeUpOptions effective = options == null ? ComposeUpOptions.builder().build() : options;
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            if (effective.forceRecreate() && effective.noRecreate())
                throw new IllegalArgumentException("forceRecreate and noRecreate cannot both be enabled.");
            if (effective.alwaysRecreateDeps() && effective.noRecreate())
                throw new IllegalArgumentException("alwaysRecreateDeps cannot be enabled with noRecreate.");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> requested = selectConfigurationServices(project, context.profiles(), effective.services());
            List<String> effectiveProfiles = effectiveProfiles(requested, context.profiles());
            List<ServiceDefinition> selected = dependencyOrderedServices(project, requested, effectiveProfiles);
            selected.forEach(ComposeService::validateServiceLabelConfiguration);
            for (ServiceDefinition service : selected) {
                validateRunnableService(service);
                int replicas = effective.scale() != null && effective.scale().containsKey(service.name())
                        ? effective.scale().get(service.name()) : replicaCount(service);
                validateContainerNameScaling(service, replicas);
            }
            java.util.Set<String> dependencies = selected.stream()
                    .flatMap(service -> serviceDependencyNames(service).stream())
                    .collect(java.util.stream.Collectors.toSet());
            List<ServiceDefinition> buildServices = selected.stream()
                    .filter(service -> effective.build() || "build".equals(effectivePullPolicy(service, effective.pull())))
                    .toList();
            if (!effective.noBuild() && !buildServices.isEmpty()) await(buildAsync(context, ComposeBuildOptions.builder()
                    .services(buildServices.stream().map(ServiceDefinition::name).toList())
                    .logConsumer(effective.logConsumer()).build()));
            try (DockerClient client = createDockerClient(context)) {
                ensureProjectResources(client, context.projectName(), project);
                if (effective.removeOrphans()) removeOrphanContainers(client, context.projectName(), project);
                Set<String> updatedServices = new java.util.LinkedHashSet<>();
                for (ServiceDefinition service : selected) {
                    if (!effective.noStart()) awaitDependencies(client, project, context.projectName(), service,
                            effective.dependencyWaitTimeoutSeconds(), effectiveProfiles);
                    List<String> previousContainerIds = serviceContainerIds(client, context.projectName(), service.name());
                    String image = service.image() == null || service.image().isBlank()
                            ? context.projectName() + "-" + service.name() : service.image();
                    String pullPolicy = effectivePullPolicy(service, effective.pull());
                    if ("build".equals(pullPolicy) && effective.noBuild() && !imageExists(client, image))
                        throw new IllegalStateException("Service '" + service.name() + "' requires a build, but noBuild is enabled and its image is missing.");
                    if (!context.offline() && shouldPullImage(client, image, pullPolicy,
                            service.configuration().get("pull_refresh_after"))) {
                        pullImage(client, context.registryAuth(), image, stringValue(service.configuration().get("platform"), null));
                    }
                    int replicas = effective.scale() != null && effective.scale().containsKey(service.name())
                            ? effective.scale().get(service.name()) : replicaCount(service);
                    boolean forceRecreate = effective.forceRecreate()
                            || effective.alwaysRecreateDeps() && dependencies.contains(service.name());
                    ensureServiceContainers(client, context, project, service, replicas,
                            forceRecreate, effective.noRecreate(), effective.renewAnonVolumes(), !effective.noStart(),
                            positiveTimeout(effective.timeoutSeconds()), effective.logConsumer());
                    List<String> currentContainerIds = serviceContainerIds(client, context.projectName(), service.name());
                    if (!effective.noStart() && !previousContainerIds.isEmpty()) {
                        boolean recreated = !previousContainerIds.equals(currentContainerIds);
                        boolean dependencyUpdated = restartDependencyNames(service).stream().anyMatch(updatedServices::contains);
                        if (dependencyUpdated && !recreated) {
                            restartContainers(client, projectContainers(client, context.projectName(), List.of(service.name()), true).stream()
                                    .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                                    .toList(), service.configuration().get("stop_grace_period") == null
                                    ? DEFAULT_STOP_TIMEOUT_SECONDS
                                    : parseDurationSeconds(String.valueOf(service.configuration().get("stop_grace_period"))));
                        }
                        if (recreated || dependencyUpdated) updatedServices.add(service.name());
                    }
                }
                if (effective.waitForCompletion() && !effective.noStart()) {
                    for (ServiceDefinition service : selected) awaitServiceRunning(client, context.projectName(), service,
                            effective.dependencyWaitTimeoutSeconds());
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
            if (!effective.detach() && !effective.noStart()) {
                ILogConsumer consumer = effective.logConsumer() == null ? new ILogConsumer() {
                    @Override public void onLog(String serviceName, String message, boolean standardError) { }
                    @Override public void onLogComplete(String serviceName) { }
                    @Override public void onStatus(String serviceName, String message) { }
                } : effective.logConsumer();
                List<String> attachedServices = selected.stream()
                        .filter(service -> !Boolean.FALSE.equals(service.configuration().get("attach")))
                        .map(ServiceDefinition::name).toList();
                if (!attachedServices.isEmpty()) {
                    await(logsAsync(context, ComposeLogsOptions.builder()
                            .services(attachedServices)
                            .follow(true).build(), consumer));
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> createAsync(ComposeProjectContext context, ComposeCreateOptions options) {
        ComposeCreateOptions effective = options == null ? ComposeCreateOptions.builder().build() : options;
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            if (effective.forceRecreate() && effective.noRecreate())
                throw new IllegalArgumentException("forceRecreate and noRecreate cannot both be enabled.");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> requested = selectConfigurationServices(project, context.profiles(), effective.services());
            List<ServiceDefinition> selected = dependencyOrderedServices(project, requested,
                    effectiveProfiles(requested, context.profiles()));
            selected.forEach(ComposeService::validateServiceLabelConfiguration);
            for (ServiceDefinition service : selected) {
                validateRunnableService(service);
                int replicas = effective.scale() != null && effective.scale().containsKey(service.name())
                        ? effective.scale().get(service.name()) : 1;
                validateContainerNameScaling(service, replicas);
            }
            List<ServiceDefinition> buildServices = selected.stream()
                    .filter(service -> effective.build() || "build".equals(effectivePullPolicy(service, effective.pull())))
                    .toList();
            if (!buildServices.isEmpty()) await(buildAsync(context, ComposeBuildOptions.builder()
                    .services(buildServices.stream().map(ServiceDefinition::name).toList()).build()));
            try (DockerClient client = createDockerClient(context)) {
                ensureProjectResources(client, context.projectName(), project);
                if (effective.removeOrphans()) removeOrphanContainers(client, context.projectName(), project);
                for (ServiceDefinition service : selected) {
                    String image = service.image() == null || service.image().isBlank()
                            ? context.projectName() + "-" + service.name() : service.image();
                    String pullPolicy = effectivePullPolicy(service, effective.pull());
                    if (!context.offline() && shouldPullImage(client, image, pullPolicy,
                            service.configuration().get("pull_refresh_after")))
                        pullImage(client, context.registryAuth(), image, stringValue(service.configuration().get("platform"), null));
                    int replicas = effective.scale() != null && effective.scale().containsKey(service.name())
                            ? effective.scale().get(service.name()) : 1;
                    ensureServiceContainers(client, context, project, service, replicas,
                            effective.forceRecreate(), effective.noRecreate(), false, false,
                            positiveTimeout(effective.timeoutSeconds()), null);
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
            return null;
        });
    }
    @Override
    public CompletableFuture<String> runAsync(ComposeProjectContext context, String serviceName, ComposeRunOptions options) {
        ComposeRunOptions effective = options == null ? ComposeRunOptions.builder().build() : options;
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            ServiceDefinition service = selectConfigurationServices(project, context.profiles(), List.of(serviceName))
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Service '" + serviceName + "' not found."));
            List<String> effectiveProfiles = effectiveProfiles(List.of(service), context.profiles());
            validateRunnableService(service);
            validateServiceLabels(service);
            try (DockerClient client = createDockerClient(context)) {
                ensureProjectResources(client, context.projectName(), project);
                if (!effective.noDeps()) {
                    for (ServiceDefinition dependency : dependencyOrderedServices(project, List.of(service), effectiveProfiles)) {
                        if (dependency.name().equals(serviceName)) continue;
                        String image = dependency.image() == null || dependency.image().isBlank()
                                ? context.projectName() + "-" + dependency.name() : dependency.image();
                        String pullPolicy = effectivePullPolicy(dependency, null);
                        if (!context.offline() && shouldPullImage(client, image, pullPolicy,
                                dependency.configuration().get("pull_refresh_after")))
                            pullImage(client, context.registryAuth(), image, stringValue(dependency.configuration().get("platform"), null));
                        ensureServiceContainers(client, context, project, dependency, replicaCount(dependency), false, false,
                                false, true, DEFAULT_STOP_TIMEOUT_SECONDS, null);
                        awaitDependencies(client, project, context.projectName(), dependency, null, effectiveProfiles);
                    }
                    awaitDependencies(client, project, context.projectName(), service, null, effectiveProfiles);
                }
                String image = service.image() == null || service.image().isBlank()
                        ? context.projectName() + "-" + service.name() : service.image();
                String pullPolicy = effectivePullPolicy(service, null);
                if (!context.offline() && shouldPullImage(client, image, pullPolicy,
                        service.configuration().get("pull_refresh_after")))
                    pullImage(client, context.registryAuth(), image, stringValue(service.configuration().get("platform"), null));

                int index = effective.index() == null ? 0 : effective.index();
                String name = effective.name() == null || effective.name().isBlank()
                        ? context.projectName() + "-" + serviceName + "-run-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                        : effective.name();
                Map<String, String> labels = new LinkedHashMap<>();
                labels.put(ComposeConstants.PROJECT_LABEL, context.projectName());
                labels.put(ComposeConstants.SERVICE_LABEL, serviceName);
                labels.put(ComposeConstants.CONTAINER_NUMBER_LABEL, Integer.toString(index));
                labels.put(ComposeConstants.ONE_OFF_LABEL, "true");
                labels.put(ComposeConstants.CONFIG_HASH_LABEL, configHash(service));
                labels.put(ComposeConstants.VERSION_LABEL, "3.0.0");
                labels.put(ComposeConstants.WORKING_DIR_LABEL, project.sourceFile().getParent().toString());
                labels.put(ComposeConstants.CONFIG_FILES_LABEL, project.sourceFile().toString());
                labels.putAll(stringMap(service.configuration().get("labels")));
                Map<String, String> runLabels = parseKeyValueOptions(effective.labels(), "label");
                validateComposeLabels(runLabels);
                labels.putAll(runLabels);

                var host = com.github.dockerjava.api.model.HostConfig.newHostConfig();
                String configuredNetworkMode = stringValue(service.configuration().get("network_mode"), "");
                host.withNetworkMode(configuredNetworkMode.isBlank() ? primaryNetwork(context.projectName(), project, service)
                        : dockerNetworkMode(client, context.projectName(), service));
                if (service.configuration().get("init") instanceof Boolean init) host.withInit(init);
                applyVolumesFrom(client, project, context.projectName(), service, host);
                applyLoggingConfig(host, service.configuration());
                applyDnsConfig(host, service.configuration());
                applyResourceConfig(host, service.configuration());
                applyDeviceCgroupRules(host, service.configuration());
                applyBlockIoConfig(host, service.configuration().get("blkio_config"));
                applyRuntimeOptions(host, service.configuration());
                List<com.github.dockerjava.api.model.Bind> binds = new ArrayList<>(serviceBinds(project, context.projectName(), service));
                for (String volume : effective.volumes() == null ? List.<String>of() : effective.volumes())
                    binds.add(parseRunBind(volume));
                if (!binds.isEmpty()) host.withBinds(binds);
                if (effective.privileged()) host.withPrivileged(true);
                if (effective.capAdd() != null && !effective.capAdd().isEmpty()) host.withCapAdd(effective.capAdd().stream()
                        .map(value -> com.github.dockerjava.api.model.Capability.valueOf(value.toUpperCase(java.util.Locale.ROOT)))
                        .toArray(com.github.dockerjava.api.model.Capability[]::new));
                if (effective.capDrop() != null && !effective.capDrop().isEmpty()) host.withCapDrop(effective.capDrop().stream()
                        .map(value -> com.github.dockerjava.api.model.Capability.valueOf(value.toUpperCase(java.util.Locale.ROOT)))
                        .toArray(com.github.dockerjava.api.model.Capability[]::new));

                var command = client.createContainerCmd(image).withName(name).withLabels(labels).withHostConfig(host)
                        .withTty(effective.tty()).withStdinOpen(effective.interactive());
                List<String> entrypoint = effective.entrypoint() == null || effective.entrypoint().isBlank()
                        ? asStringList(service.configuration().get("entrypoint")) : List.of(effective.entrypoint());
                if (!entrypoint.isEmpty()) command.withEntrypoint(entrypoint);
                List<String> cmd = effective.command() == null ? asStringList(service.configuration().get("command")) : effective.command();
                if (!cmd.isEmpty()) command.withCmd(cmd);
                Map<String, String> environment = new LinkedHashMap<>(stringMap(service.configuration().get("environment")));
                environment.putAll(parseKeyValueOptions(effective.env(), "environment"));
                if (!environment.isEmpty()) command.withEnv(environment.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
                String workingDir = effective.workdir() == null ? stringValue(service.configuration().get("working_dir"), null) : effective.workdir();
                if (workingDir != null) command.withWorkingDir(workingDir);
                String user = effective.user() == null ? stringValue(service.configuration().get("user"), null) : effective.user();
                if (user != null) command.withUser(user);
                if (service.configuration().get("stop_signal") != null)
                    command.withStopSignal(String.valueOf(service.configuration().get("stop_signal")));
                if (service.configuration().get("stop_grace_period") != null)
                    command.withStopTimeout(parseDurationSeconds(String.valueOf(service.configuration().get("stop_grace_period"))));
                List<com.github.dockerjava.api.model.ExposedPort> exposed = serviceExposedPorts(service);
                if (!exposed.isEmpty()) command.withExposedPorts(exposed);
                List<com.github.dockerjava.api.model.PortBinding> ports = servicePorts(service);
                if (!ports.isEmpty()) command.withPortBindings(ports);
                if (networkModeService(service) == null) {
                    String primaryNetwork = serviceNetworks(service).getFirst();
                    List<String> primaryAliases = networkAliases(service, primaryNetwork, effective.useNetworkAliases());
                    if (!primaryAliases.isEmpty()) command.withAliases(primaryAliases);
                    applyPrimaryNetworkOptions(command, service, primaryNetwork);
                }
                String containerId = command.exec().getId();
            connectAdditionalNetworks(client, context.projectName(), project, service, containerId, effective.useNetworkAliases());
                client.startContainerCmd(containerId).exec();

                if (!effective.detach()) {
                    var callback = client.logContainerCmd(containerId).withFollowStream(true).withStdOut(true).withStdErr(true)
                            .withTailAll().exec(new ResultCallback.Adapter<Frame>() {
                                @Override public void onNext(Frame frame) {
                                    if (frame.getStreamType() == StreamType.STDERR) System.err.print(new String(frame.getPayload(), StandardCharsets.UTF_8));
                                    else System.out.print(new String(frame.getPayload(), StandardCharsets.UTF_8));
                                }
                            });
                    await(callback);
                    if (options == null || effective.remove()) client.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(false).exec();
                }
                return containerId;
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }
    @Override public CompletableFuture<ExecResult> execAsync(
            ComposeProjectContext context, String service, ComposeExecOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(options, "options");
            if (service == null || service.isBlank()) throw new IllegalArgumentException("Service must not be blank.");
            if (options.command().isEmpty()) throw new IllegalArgumentException("Exec command must not be empty.");
            try (DockerClient client = createDockerClient(context)) {
                List<Container> running = projectContainers(client, context.projectName(), List.of(service), false).stream()
                        .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                        .sorted(containerOrder()).toList();
                Container container = selectFromContainers(running, service, options.index());
                var create = client.execCreateCmd(container.getId())
                        .withCmd(options.command().toArray(String[]::new))
                        .withAttachStdout(true).withAttachStderr(true)
                        .withAttachStdin(options.interactive()).withTty(options.tty())
                        .withPrivileged(options.privileged());
                if (options.env() != null) create.withEnv(options.env());
                if (options.user() != null) create.withUser(options.user());
                if (options.workdir() != null) create.withWorkingDir(options.workdir());
                String execId = create.exec().getId();
                var start = client.execStartCmd(execId).withDetach(options.detach()).withTty(options.tty());
                if (options.interactive()) start.withStdIn(System.in);
                if (options.detach()) {
                    start.exec(new Adapter<>());
                    return new ExecResult(0, "", "");
                }
                ExecOutput callback = new ExecOutput();
                start.exec(callback);
                await(callback);
                var inspected = client.inspectExecCmd(execId).exec();
                int exitCode = inspected.getExitCode() == null ? -1 : inspected.getExitCode();
                return new ExecResult(exitCode, callback.stdout.toString(), callback.stderr.toString());
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    @Override public CompletableFuture<Void> attachAsync(ComposeProjectContext context, ComposeAttachOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            ComposeAttachOptions effective = options == null ? ComposeAttachOptions.builder().build() : options;
            try (DockerClient client = createDockerClient(context)) {
                List<Container> running = projectContainers(client, context.projectName(),
                                effective.serviceName() == null ? null : List.of(effective.serviceName()), false).stream()
                        .filter(ComposeService::isRunning)
                        .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                        .sorted(containerOrder()).toList();
                Container container;
                if (effective.serviceName() == null) {
                    if (running.size() != 1) throw new IllegalStateException("Attach without a service requires exactly one running project container.");
                    container = running.getFirst();
                } else {
                    container = selectFromContainers(running, effective.serviceName(), effective.index());
                }
                var command = client.attachContainerCmd(container.getId()).withLogs(false).withFollowStream(true)
                        .withStdOut(effective.stdout()).withStdErr(effective.stderr());
                if (effective.stdin()) command.withStdIn(System.in);
                ResultCallback.Adapter<Frame> callback = command.exec(new Adapter<>() {
                    @Override public void onNext(Frame frame) {
                        byte[] payload = frame.getPayload();
                        if (payload == null || payload.length == 0) return;
                        java.io.PrintStream stream = frame.getStreamType() == StreamType.STDERR ? System.err : System.out;
                        stream.write(payload, 0, payload.length);
                        stream.flush();
                    }
                });
                await(callback);
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
            return null;
        });
    }
    @Override
    public CompletableFuture<CopyResult> copyAsync(ComposeProjectContext context, ComposeCopyOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(options, "options");
            CopyPath source = CopyPath.parse(options.source());
            CopyPath destination = CopyPath.parse(options.destination());
            if (source.container() == destination.container())
                throw new IllegalArgumentException("Exactly one copy path must use the 'service:/container/path' form.");
            try (DockerClient client = createDockerClient(context)) {
                if (source.container()) {
                    Container container = archiveContainer(client, context.projectName(), source.service(), options.index(), options.all());
                    String archivePath = source.path();
                    try (InputStream archive = client.copyArchiveFromContainerCmd(container.getId(), archivePath).exec()) {
                        Path target = Path.of(destination.path()).toAbsolutePath().normalize();
                        ensureNoSymlinkAncestors(target);
                        Files.createDirectories(target);
                        CopyExtraction extraction = extractTar(archive, target);
                        return new CopyResult(null, extraction.directory(), extraction.bytes(), 0);
                    }
                }

                Path localSource = Path.of(source.path()).toAbsolutePath().normalize();
                if (!Files.exists(localSource, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new java.nio.file.NoSuchFileException(localSource.toString());
                Path copySource = resolveCopySource(localSource, options.followLink());
                rejectSymlinkTree(copySource);
                Path tempArchive = Files.createTempFile("jcompose-copy-", ".tar");
                long bytes = 0;
                try {
                    try (var file = Files.newOutputStream(tempArchive);
                         var tar = new TarArchiveOutputStream(file)) {
                        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                        String root = localSource.getFileName() == null ? null : localSource.getFileName().toString();
                        if (root == null || root.isBlank()) throw new IllegalArgumentException("The copy source must not be a filesystem root.");
                        bytes = appendTarEntry(tar, copySource, root);
                        tar.finish();
                    }
                    List<Container> targets = serviceArchiveContainers(client, context.projectName(), destination.service(), options.all());
                    if (options.index() != null) {
                        if (options.index() <= 0) throw new IllegalArgumentException("Container indexes are 1-based.");
                        targets = targets.stream().filter(c -> Integer.toString(options.index()).equals(label(c, ComposeConstants.CONTAINER_NUMBER_LABEL))).toList();
                    }
                    if (targets.isEmpty()) throw new IllegalStateException("No container found for service '" + destination.service() + "'.");
                    for (Container target : targets) {
                        try (InputStream tar = Files.newInputStream(tempArchive)) {
                            client.copyArchiveToContainerCmd(target.getId()).withRemotePath(destination.path())
                                    .withTarInputStream(tar).withNoOverwriteDirNonDir(true)
                                    .withCopyUIDGID(options.copyUidGid()).exec();
                        }
                    }
                    return new CopyResult(null, Files.isDirectory(copySource), Math.multiplyExact(bytes, targets.size()), 0);
                } finally {
                    Files.deleteIfExists(tempArchive);
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client or copy archive.", exception);
            }
        });
    }
    @Override public Flow.Publisher<ComposeEvent> eventsAsync(ComposeProjectContext c, ComposeEventsOptions o) { return eventsPublisher(c, o); }
    @Override public CompletableFuture<List<ServiceStatus>> scaleAsync(ComposeProjectContext context, ComposeScaleOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(options, "options");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<String> requested = List.copyOf(options.services().keySet());
            List<ServiceDefinition> selected = selectConfigurationServices(project, context.profiles(), requested);
            Map<String, ServiceDefinition> definitions = new LinkedHashMap<>();
            selected.forEach(service -> definitions.put(service.name(), service));
            for (Map.Entry<String, Integer> entry : options.services().entrySet()) {
                ServiceDefinition service = definitions.get(entry.getKey());
                if (service != null) {
                    validateRunnableService(service);
                    validateContainerNameScaling(service, entry.getValue());
                }
            }
            try (DockerClient client = createDockerClient(context)) {
                ensureProjectResources(client, context.projectName(), project);
                List<ServiceStatus> results = new ArrayList<>();
                for (Map.Entry<String, Integer> entry : options.services().entrySet()) {
                    String serviceName = entry.getKey();
                    int desired = entry.getValue();
                    if (desired < 0) throw new IllegalArgumentException("Replica count must not be negative for service '" + serviceName + "'.");
                    ensureServiceContainers(client, context, project, definitions.get(serviceName), desired,
                            false, false, false, true, positiveTimeout(options.timeoutSeconds()), null);
                    List<Container> containers = projectContainers(client, context.projectName(), List.of(serviceName), true);
                    long running = containers.stream()
                            .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                            .filter(ComposeService::isRunning).count();
                    results.add(new ServiceStatus(serviceName, desired, Math.toIntExact(running), List.of(), List.of()));
                }
                return List.copyOf(results);
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }
    @Override public CompletableFuture<WaitResult> waitAsync(ComposeProjectContext context, ComposeWaitOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            List<String> selectedServices = selectExistingServices(context, options == null ? null : options.services()).serviceNames();
            try (DockerClient client = createDockerClient(context)) {
                List<Container> containers = projectContainers(client, context.projectName(),
                                selectedServices, false)
                        .stream().filter(ComposeService::isRunning)
                        .sorted(containerOrder()).toList();
                Map<String, CompletableFuture<Integer>> waits = new LinkedHashMap<>();
                boolean downOnContainerExit = options != null && options.downProjectOnContainerExit();
                CompletableFuture<Void> firstContainerExit = new CompletableFuture<>();
                CompletableFuture<Void> downOnExit = downOnContainerExit
                        ? firstContainerExit.thenCompose(ignored -> downAsync(new ComposeProjectContext(
                                context.projectName(), context.workingDirectory(), context.composeFileName(), null,
                                context.socketPath(), context.registryAuth(), context.offline(), context.compatibility()), null))
                        : null;
                for (Container container : containers) {
                    CompletableFuture<Integer> result = new CompletableFuture<>();
                    client.waitContainerCmd(container.getId()).exec(new Adapter<>() {
                        @Override public void onNext(com.github.dockerjava.api.model.WaitResponse response) {
                            firstContainerExit.complete(null);
                            result.complete(response.getStatusCode() == null ? -1 : response.getStatusCode());
                        }
                        @Override public void onError(Throwable error) {
                            if (error instanceof DockerException) result.complete(-1);
                            else result.completeExceptionally(error);
                        }
                    });
                    waits.put(container.getId(), result);
                }
                CompletableFuture<Void> allWaits = CompletableFuture.allOf(waits.values().toArray(CompletableFuture[]::new));
                if (downOnExit != null) {
                    await(CompletableFuture.anyOf(allWaits, downOnExit));
                    if (firstContainerExit.isDone()) await(downOnExit);
                }
                await(allWaits);
                List<String> ids = waits.keySet().stream().sorted().toList();
                Map<String, Integer> exitCodes = new LinkedHashMap<>();
                for (String id : ids) exitCodes.put(id, await(waits.get(id)));
                int aggregateCode = exitCodes.values().stream().filter(code -> code != 0).findFirst().orElse(0);
                return new WaitResult(exitCodes, aggregateCode);
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }
    @Override public Flow.Publisher<WatchEvent> watchAsync(ComposeProjectContext context, ComposeWatchOptions options) {
        java.util.Objects.requireNonNull(context, "context");
        ComposeWatchOptions effective = options == null ? ComposeWatchOptions.builder().build() : options;
        if (effective.prune()) throw new UnsupportedOperationException("Pruning build artifacts is not supported by watch.");
        ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
        Path projectDirectory = project.sourceFile().getParent();
        List<BuildContextWatcher.Registration> registrations = new ArrayList<>();
        for (ServiceDefinition service : selectConfigurationServices(project, context.profiles(), effective.services())) {
            Object buildValue = service.build();
            if (buildValue == null || Boolean.FALSE.equals(buildValue)) continue;
            Map<String, Object> build = buildValue instanceof Map<?, ?> raw
                    ? stringKeyMap(raw) : Map.of("context", buildValue);
            String directory = stringValue(build.get("context"), ".");
            Path buildDirectory = projectDirectory.resolve(directory).normalize();
            registrations.add(new BuildContextWatcher.Registration(service.name(), buildDirectory));
        }
        return new BuildContextWatcher(registrations);
    }
    @Override public CompletableFuture<Void> exportAsync(ComposeProjectContext context, ComposeExportOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(options, "options");
            if (options.service().isBlank()) throw new IllegalArgumentException("Service must not be blank.");
            if (options.outputPath().isBlank()) throw new IllegalArgumentException("Output path must not be blank.");
            try (DockerClient client = createDockerClient(context)) {
                Container container = selectServiceContainer(client, context.projectName(), options.service(), options.index());
                Path outputPath = Path.of(options.outputPath()).toAbsolutePath().normalize();
                Path parent = outputPath.getParent();
                if (parent == null) throw new IllegalArgumentException("The output path must include a parent directory.");
                Files.createDirectories(parent);
                try (InputStream archive = client.exportContainerCmd(container.getId()).exec();
                     OutputStream output = Files.newOutputStream(outputPath)) {
                    archive.transferTo(output);
                }
                return null;
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not export container filesystem.", exception);
            }
        });
    }

    @Override public CompletableFuture<String> commitAsync(ComposeProjectContext context, ComposeCommitOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(options, "options");
            if (options.service().isBlank()) throw new IllegalArgumentException("Service must not be blank.");
            List<String> changes = ImageCommitParametersFactory.toDockerfileChanges(options.changes());
            String reference = options.reference() == null || options.reference().isBlank()
                    ? context.projectName() + "-" + options.service() + ":latest" : options.reference();
            int slash = reference.lastIndexOf('/');
            int colon = reference.lastIndexOf(':');
            String repository = colon > slash ? reference.substring(0, colon) : reference;
            String tag = colon > slash ? reference.substring(colon + 1) : "latest";
            if (repository.isBlank() || tag.isBlank()) throw new IllegalArgumentException("Image reference must contain a repository and a non-empty tag.");
            try (DockerClient client = DockerClientFactory.create(context, changes)) {
                Container container = selectServiceContainer(client, context.projectName(), options.service(), options.index());
                var command = client.commitCmd(container.getId())
                        .withRepository(repository)
                        .withTag(tag)
                        .withPause(options.pause());
                if (options.author() != null) command.withAuthor(options.author());
                if (options.message() != null) command.withMessage(options.message());
                command.exec();
                return reference;
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    private static Container selectServiceContainer(DockerClient client, String project, String service, Integer index) {
        if (index != null && index <= 0) throw new IllegalArgumentException("Container indexes are 1-based.");
        List<Container> containers = projectContainers(client, project, List.of(service), true).stream()
                .filter(container -> !"true".equalsIgnoreCase(label(container, ComposeConstants.ONE_OFF_LABEL)))
                .sorted(containerOrder()).toList();
        return selectFromContainers(containers, service, index);
    }

    private static Container selectFromContainers(List<Container> containers, String service, Integer index) {
        if (index != null && index <= 0) throw new IllegalArgumentException("Container indexes are 1-based.");
        if (index != null) {
            return containers.stream().filter(container -> replicaNumber(container) == index).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No container at index " + index + " for service '" + service + "'."));
        }
        return containers.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No container found for service '" + service + "'."));
    }

    private static final class ExecOutput extends Adapter<Frame> {
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();

        @Override public synchronized void onNext(Frame frame) {
            byte[] payload = frame.getPayload();
            if (payload == null || payload.length == 0) return;
            StringBuilder target = frame.getStreamType() == StreamType.STDERR ? stderr : stdout;
            target.append(new String(payload, StandardCharsets.UTF_8));
        }
    }
    @Override public CompletableFuture<List<PublishResult>> publishAsync(
            ComposeProjectContext context, String repository, ComposePublishOptions options) {
        return supply(() -> {
            java.util.Objects.requireNonNull(context, "context");
            if (context.offline()) throw new IllegalStateException("Cannot publish images when the Compose project context is offline.");
            if (repository == null || repository.isBlank()) throw new IllegalArgumentException("Repository must not be blank.");
            if (options != null && (options.resolveImageDigests() || options.withEnvironment()
                    || options.ocIVersion() != null && !options.ocIVersion().isBlank() || options.insecureRegistry()))
                throw new UnsupportedOperationException("Digest resolution, environment packaging, OCI version selection, and insecure registries are not supported by this publish implementation.");
            ComposeProject project = loader.loadProject(context.workingDirectory(), context.composeFileName());
            List<ServiceDefinition> selected = selectConfigurationServices(project, context.profiles(), null);
            try (DockerClient client = createDockerClient(context)) {
                List<PublishResult> results = new ArrayList<>();
                for (ServiceDefinition service : selected) {
                    String sourceImage = service.image();
                    if (sourceImage == null || sourceImage.isBlank()) {
                        results.add(new PublishResult(service.name(), null, null, false, false,
                                "Service does not define an image reference."));
                        continue;
                    }
                    String taggedImage = repository + ":" + service.name();
                    try {
                        client.tagImageCmd(sourceImage, repository, service.name()).exec();
                    } catch (RuntimeException failure) {
                        results.add(publishResult(service.name(), sourceImage, taggedImage, false, false,
                                "Tag failed: " + failure.getMessage()));
                        continue;
                    }
                    try {
                        pushImage(client, context.registryAuth(), taggedImage);
                        results.add(publishResult(service.name(), sourceImage, taggedImage, true, true, null));
                    } catch (RuntimeException failure) {
                        if (failure instanceof java.util.concurrent.CancellationException) throw failure;
                        results.add(publishResult(service.name(), sourceImage, taggedImage, true, false,
                                "Push failed: " + failure.getMessage()));
                    }
                }
                return List.copyOf(results);
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException("Could not close Docker Engine client.", exception);
            }
        });
    }

    private static PublishResult publishResult(String service, String sourceImage, String taggedImage,
                                                boolean tagged, boolean pushed, String error) {
        return new PublishResult(service, sourceImage, taggedImage, tagged, pushed, error);
    }
}
