package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Asynchronous in-process operations over project-labeled Docker Engine resources. */
public interface IComposeService {
    CompletableFuture<ComposeProject> loadProject(ComposeProjectContext context);
    CompletableFuture<Void> buildAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> upAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> downAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> createAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> startAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> stopAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> restartAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> pullAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> pushAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> killAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<String> runAsync(ComposeProjectContext c, String service, ComposeOperationOptions o);
    CompletableFuture<Void> removeAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<OperationResult> execAsync(ComposeProjectContext c, String service, ComposeOperationOptions o);
    CompletableFuture<Void> attachAsync(ComposeProjectContext c, String service, ComposeOperationOptions o);
    CompletableFuture<OperationResult> copyAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> pauseAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> unpauseAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<List<OperationResult>> psAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<List<OperationResult>> listAsync(ComposeOperationOptions o);
    CompletableFuture<List<OperationResult>> topAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<List<OperationResult>> imagesAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<OperationResult> portAsync(ComposeProjectContext c, String service, int port, ComposeOperationOptions o);
    CompletableFuture<Void> logsAsync(ComposeProjectContext c, ComposeOperationOptions o);
    Flow.Publisher<OperationResult> eventsAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<List<OperationResult>> scaleAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<List<OperationResult>> waitAsync(ComposeProjectContext c, ComposeOperationOptions o);
    Flow.Publisher<OperationResult> watchAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<Void> exportAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<String> commitAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<String> vizAsync(ComposeProjectContext c);
    CompletableFuture<OperationResult> generateAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<List<OperationResult>> volumesAsync(ComposeProjectContext c, ComposeOperationOptions o);
    CompletableFuture<List<OperationResult>> publishAsync(ComposeProjectContext c, String repository, ComposeOperationOptions o);
}
