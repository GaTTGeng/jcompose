package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.WaitResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class WaitResultCollector {
    private WaitResultCollector() { }

    static CompletableFuture<WaitResult> collect(Map<String, CompletableFuture<Integer>> waits) {
        return CompletableFuture.allOf(waits.values().toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<String, Integer> exitCodes = new LinkedHashMap<>();
                    waits.keySet().stream().sorted().forEach(id -> exitCodes.put(id, waits.get(id).join()));
                    int code = exitCodes.values().stream().filter(exitCode -> exitCode != 0).findFirst().orElse(0);
                    return new WaitResult(exitCodes, code);
                });
    }
}
