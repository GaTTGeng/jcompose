package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.WaitResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WaitResultCollectorTest {
    @Test
    void collectsExitCodesAndAggregatesByContainerIdRegardlessOfCompletionOrder() {
        Map<String, CompletableFuture<Integer>> waits = new LinkedHashMap<>();
        waits.put("container-c", new CompletableFuture<>());
        waits.put("container-a", new CompletableFuture<>());
        waits.put("container-b", new CompletableFuture<>());

        CompletableFuture<WaitResult> result = WaitResultCollector.collect(waits);
        waits.get("container-b").complete(9);
        assertFalse(result.isDone());
        waits.get("container-a").complete(3);
        waits.get("container-c").complete(7);

        WaitResult collected = result.join();
        assertEquals(Map.of("container-a", 3, "container-b", 9, "container-c", 7), collected.exitCodes());
        assertEquals(List.of("container-a", "container-b", "container-c"), List.copyOf(collected.exitCodes().keySet()));
        assertEquals(3, collected.code());
    }
}
