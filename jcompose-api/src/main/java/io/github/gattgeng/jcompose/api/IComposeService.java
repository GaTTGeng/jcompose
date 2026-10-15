package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Asynchronous in-process operations over project-labeled Docker Engine resources.
 * Cancelling an operation future with {@code cancel(true)} interrupts its worker and closes
 * Docker clients owned by that operation. Cancellation of a Docker request already in progress
 * depends on the configured transport responding to client closure or thread interruption.
 */
public interface IComposeService {
    CompletableFuture<ComposeProjectConfig> loadProject(ComposeProjectContext context);
    CompletableFuture<Void> buildAsync(ComposeProjectContext c, ComposeBuildOptions o);
    CompletableFuture<Void> upAsync(ComposeProjectContext c, ComposeUpOptions o);
    CompletableFuture<Void> downAsync(ComposeProjectContext c, ComposeDownOptions o);
    CompletableFuture<Void> createAsync(ComposeProjectContext c, ComposeCreateOptions o);
    CompletableFuture<Void> startAsync(ComposeProjectContext c, ComposeStartOptions o);
    CompletableFuture<Void> stopAsync(ComposeProjectContext c, ComposeStopOptions o);
    CompletableFuture<Void> restartAsync(ComposeProjectContext c, ComposeRestartOptions o);
    CompletableFuture<Void> pullAsync(ComposeProjectContext c, ComposePullOptions o);
    CompletableFuture<Void> pushAsync(ComposeProjectContext c, ComposePushOptions o);
    CompletableFuture<Void> killAsync(ComposeProjectContext c, ComposeKillOptions o);
    CompletableFuture<String> runAsync(ComposeProjectContext c, String service, ComposeRunOptions o);
    CompletableFuture<Void> removeAsync(ComposeProjectContext c, ComposeRemoveOptions o);
    CompletableFuture<ExecResult> execAsync(ComposeProjectContext c, String service, ComposeExecOptions o);
    CompletableFuture<Void> attachAsync(ComposeProjectContext c, ComposeAttachOptions o);
    CompletableFuture<CopyResult> copyAsync(ComposeProjectContext c, ComposeCopyOptions o);
    CompletableFuture<Void> pauseAsync(ComposeProjectContext c, ComposePauseOptions o);
    CompletableFuture<Void> unpauseAsync(ComposeProjectContext c, ComposePauseOptions o);
    CompletableFuture<List<ContainerSummary>> psAsync(ComposeProjectContext c, ComposePsOptions o);
    CompletableFuture<List<Stack>> listAsync(ComposeListOptions o);
    CompletableFuture<List<ContainerProcSummary>> topAsync(ComposeProjectContext c, ComposeTopOptions o);
    CompletableFuture<List<ImageSummary>> imagesAsync(ComposeProjectContext c, ComposeImagesOptions o);
    CompletableFuture<PortResult> portAsync(ComposeProjectContext c, String service, int port, ComposePortOptions o);
    CompletableFuture<Void> logsAsync(ComposeProjectContext c, ComposeLogsOptions o, ILogConsumer consumer);
    Flow.Publisher<ComposeEvent> eventsAsync(ComposeProjectContext c, ComposeEventsOptions o);
    CompletableFuture<List<ServiceStatus>> scaleAsync(ComposeProjectContext c, ComposeScaleOptions o);
    CompletableFuture<WaitResult> waitAsync(ComposeProjectContext c, ComposeWaitOptions o);
    Flow.Publisher<WatchEvent> watchAsync(ComposeProjectContext c, ComposeWatchOptions o);
    CompletableFuture<Void> exportAsync(ComposeProjectContext c, ComposeExportOptions o);
    CompletableFuture<String> commitAsync(ComposeProjectContext c, ComposeCommitOptions o);
    CompletableFuture<String> vizAsync(ComposeProjectContext c);
    CompletableFuture<ComposeProjectConfig> generateAsync(ComposeProjectContext c, ComposeGenerateOptions o);
    CompletableFuture<List<VolumesSummary>> volumesAsync(ComposeProjectContext c, ComposeVolumesOptions o);
    CompletableFuture<List<PublishResult>> publishAsync(ComposeProjectContext c, String repository, ComposePublishOptions o);
}
