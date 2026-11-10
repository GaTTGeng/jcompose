package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * In-process configuration and asynchronous operations over project-labeled Docker Engine resources.
 * Cancelling an operation future with {@code cancel(true)} interrupts its worker and closes
 * Docker clients owned by that operation. Cancellation of a Docker request already in progress
 * depends on the configured transport responding to client closure or thread interruption.
 */
public interface IComposeService {
    /** Loads the selected Compose configuration without connecting to Docker.
     * @param context project context and configuration source
     * @return loaded project configuration and diagnostics */
    ComposeProjectConfig loadProject(ComposeProjectContext context);

    /** Loads the selected Compose configuration asynchronously without connecting to Docker.
     * @param context project context and configuration source
     * @return a future containing the loaded project configuration and diagnostics */
    CompletableFuture<ComposeProjectConfig> loadProjectAsync(ComposeProjectContext context);

    /** Builds images for services with a {@code build} section.
     * @param c project context and resource scope
     * @param o build options, or {@code null} for defaults
     * @return a future completed when selected builds finish */
    CompletableFuture<Void> buildAsync(ComposeProjectContext c, ComposeBuildOptions o);

    /** Creates or reconciles and starts selected project services and their dependencies.
     * @param c project context and resource scope
     * @param o startup options, or {@code null} for defaults
     * @return a future completed when startup and requested readiness waits finish */
    CompletableFuture<Void> upAsync(ComposeProjectContext c, ComposeUpOptions o);

    /** Stops and removes project containers, and removes project networks when taking down the whole project.
     * @param c project context and resource scope
     * @param o teardown options, or {@code null} for defaults
     * @return a future completed when teardown finishes */
    CompletableFuture<Void> downAsync(ComposeProjectContext c, ComposeDownOptions o);

    /** Creates project service containers and required infrastructure in dependency order without starting them.
     * @param c project context and resource scope
     * @param o creation options, or {@code null} for defaults
     * @return a future completed when selected containers are created */
    CompletableFuture<Void> createAsync(ComposeProjectContext c, ComposeCreateOptions o);

    /** Starts existing containers for selected services in dependency order.
     * @param c project context and resource scope
     * @param o start options, or {@code null} for defaults
     * @return a future completed when start and requested readiness waits finish */
    CompletableFuture<Void> startAsync(ComposeProjectContext c, ComposeStartOptions o);

    /** Stops running containers for selected project services.
     * @param c project context and resource scope
     * @param o stop options, or {@code null} for defaults
     * @return a future completed when selected containers stop */
    CompletableFuture<Void> stopAsync(ComposeProjectContext c, ComposeStopOptions o);

    /** Restarts containers for selected project services.
     * @param c project context and resource scope
     * @param o restart options, or {@code null} for defaults
     * @return a future completed when selected containers restart */
    CompletableFuture<Void> restartAsync(ComposeProjectContext c, ComposeRestartOptions o);

    /** Pulls images referenced by selected project services.
     * @param c project context and resource scope
     * @param o pull options, or {@code null} for defaults
     * @return a future completed when image pulls finish */
    CompletableFuture<Void> pullAsync(ComposeProjectContext c, ComposePullOptions o);

    /** Pushes images configured for selected project services.
     * @param c project context and resource scope
     * @param o push options, or {@code null} for defaults
     * @return a future completed when image pushes finish */
    CompletableFuture<Void> pushAsync(ComposeProjectContext c, ComposePushOptions o);

    /** Sends the configured signal to selected project containers.
     * @param c project context and resource scope
     * @param o kill options, or {@code null} for defaults (which use {@code SIGKILL})
     * @return a future completed when signal requests finish */
    CompletableFuture<Void> killAsync(ComposeProjectContext c, ComposeKillOptions o);

    /** Creates and starts a one-off container for a service, returning its container ID.
     * @param c project context and resource scope
     * @param service service name
     * @param o run options, or {@code null} for defaults
     * @return a future containing the one-off container ID */
    CompletableFuture<String> runAsync(ComposeProjectContext c, String service, ComposeRunOptions o);

    /** Removes containers for selected project services.
     * @param c project context and resource scope
     * @param o remove options, or {@code null} for defaults
     * @return a future completed when selected containers are removed */
    CompletableFuture<Void> removeAsync(ComposeProjectContext c, ComposeRemoveOptions o);

    /** Executes a command in a service container and captures its output and exit status.
     * @param c project context and resource scope
     * @param service service name
     * @param o exec options
     * @return a future containing captured output and the command exit code */
    CompletableFuture<ExecResult> execAsync(ComposeProjectContext c, String service, ComposeExecOptions o);

    /** Streams output from a service container until the stream ends or the operation is cancelled.
     * @param c project context and resource scope
     * @param o attach options
     * @return a future completed when the attach stream ends */
    CompletableFuture<Void> attachAsync(ComposeProjectContext c, ComposeAttachOptions o);

    /** Copies files between the host and a service container using Docker Engine archive operations.
     * @param c project context and resource scope
     * @param o copy options; exactly one path must use the {@code service:/container/path} form
     * @return a future containing the number of bytes copied and the resolved paths */
    CompletableFuture<CopyResult> copyAsync(ComposeProjectContext c, ComposeCopyOptions o);

    /** Pauses selected project service containers.
     * @param c project context and resource scope
     * @param o service selection, or {@code null} for defaults
     * @return a future completed when pause requests finish */
    CompletableFuture<Void> pauseAsync(ComposeProjectContext c, ComposePauseOptions o);

    /** Resumes selected paused project service containers.
     * @param c project context and resource scope
     * @param o service selection, or {@code null} for defaults
     * @return a future completed when unpause requests finish */
    CompletableFuture<Void> unpauseAsync(ComposeProjectContext c, ComposePauseOptions o);

    /** Lists project containers with state, ports, and labels.
     * @param c project context and resource scope
     * @param o listing options, or {@code null} for defaults
     * @return a future containing the selected containers */
    CompletableFuture<List<ContainerSummary>> psAsync(ComposeProjectContext c, ComposePsOptions o);

    /** Lists Docker projects and summarizes their project-labeled containers.
     * @param o listing options, or {@code null} for defaults
     * @return a future containing the visible project summaries */
    CompletableFuture<List<Stack>> listAsync(ComposeListOptions o);

    /** Lists processes in running containers for selected project services.
     * @param c project context and resource scope
     * @param o process-list options, or {@code null} for defaults
     * @return a future containing one process summary per running container */
    CompletableFuture<List<ContainerProcSummary>> topAsync(ComposeProjectContext c, ComposeTopOptions o);

    /** Lists images used by selected project services.
     * @param c project context and resource scope
     * @param o image-list options, or {@code null} for defaults
     * @return a future containing the matching image summaries */
    CompletableFuture<List<ImageSummary>> imagesAsync(ComposeProjectContext c, ComposeImagesOptions o);

    /** Resolves the host address and published port for a service container port.
     * @param c project context and resource scope
     * @param service service name
     * @param port container port
     * @param o port options, or {@code null} for defaults
     * @return a future containing the resolved host and port */
    CompletableFuture<PortResult> portAsync(ComposeProjectContext c, String service, int port, ComposePortOptions o);

    /** Streams logs from selected project containers to a consumer.
     * @param c project context and resource scope
     * @param o log-selection and formatting options, or {@code null} for defaults
     * @param consumer receives log entries; {@code null} discards them
     * @return a future completed when the requested log stream ends */
    CompletableFuture<Void> logsAsync(ComposeProjectContext c, ComposeLogsOptions o, ILogConsumer consumer);

    /** Publishes live Docker Engine events for resources owned by this project.
     * @param c project context and resource scope
     * @param o event filters, or {@code null} for defaults
     * @return a publisher that emits project-scoped events until cancelled or closed */
    Flow.Publisher<ComposeEvent> eventsAsync(ComposeProjectContext c, ComposeEventsOptions o);

    /** Adjusts replica counts for selected services and returns their resulting status.
     * @param c project context and resource scope
     * @param o target replica counts and service options
     * @return a future containing service status after scaling */
    CompletableFuture<List<ServiceStatus>> scaleAsync(ComposeProjectContext c, ComposeScaleOptions o);

    /** Waits for selected project containers to exit and collects their exit codes.
     * @param c project context and resource scope
     * @param o wait options, or {@code null} for defaults
     * @return a future containing per-container and aggregate exit codes */
    CompletableFuture<WaitResult> waitAsync(ComposeProjectContext c, ComposeWaitOptions o);

    /** Watches selected service build contexts and emits file-change events.
     * @param c project context and resource scope
     * @param o watch options, or {@code null} for defaults
     * @return a publisher of file changes that ends when cancelled or closed */
    Flow.Publisher<WatchEvent> watchAsync(ComposeProjectContext c, ComposeWatchOptions o);

    /** Exports a selected service container filesystem as a local archive.
     * @param c project context and resource scope
     * @param o export options
     * @return a future completed when the archive is written */
    CompletableFuture<Void> exportAsync(ComposeProjectContext c, ComposeExportOptions o);

    /** Commits a service container to an image and returns the resulting image reference.
     * @param c project context and resource scope
     * @param o commit options
     * @return a future containing the new image reference */
    CompletableFuture<String> commitAsync(ComposeProjectContext c, ComposeCommitOptions o);

    /** Renders the service dependency graph as Graphviz DOT text.
     * @param c project context and resource scope
     * @return a future containing the DOT graph */
    CompletableFuture<String> vizAsync(ComposeProjectContext c);

    /** Returns a project summary and normalized YAML snapshot of selected services.
     * @param c project context and resource scope
     * @param o generation options, or {@code null} for defaults
     * @return a future containing the project config and rendered YAML */
    CompletableFuture<ComposeProjectConfig> generateAsync(ComposeProjectContext c, ComposeGenerateOptions o);

    /** Lists Docker volumes owned by this project.
     * @param c project context and resource scope
     * @param o volume-list options, or {@code null} for defaults
     * @return a future containing the matching volume summaries */
    CompletableFuture<List<VolumesSummary>> volumesAsync(ComposeProjectContext c, ComposeVolumesOptions o);

    /** Publishes a normalized Compose configuration artifact to an OCI registry.
     * @param c project context and resource scope
     * @param repository OCI repository to receive the artifact
     * @param o publication options, or {@code null} for defaults
     * @return a future containing the published artifact result */
    CompletableFuture<PublishResult> publishAsync(ComposeProjectContext c, String repository, ComposePublishOptions o);
    /**
     * Tags each selected service image as {@code repository:service} and pushes it, returning one outcome per service.
     * The ComposeSharp 3.0.0 option flags are currently not applied, matching that source implementation.
     * @param c project context and resource scope
     * @param repository image repository used for service tags
     * @param o publication options, or {@code null} for defaults
     * @return a future containing per-service tag and push outcomes
     */
    CompletableFuture<List<ImagePublishResult>> publishImagesAsync(ComposeProjectContext c, String repository, ComposePublishOptions o);
}
