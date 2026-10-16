package io.github.gattgeng.jcompose.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposeFileMergerEnvironmentTest {
    @TempDir
    Path directory;

    @Test
    void perCallOverridesPrecedeDotEnvWithoutChangingProcessEnvironment() throws Exception {
        String variable = "JCOMPOSE_MERGE_OVERRIDE_" + Long.toUnsignedString(System.nanoTime());
        Files.writeString(directory.resolve(".env"), variable + "=from-dotenv\n");
        Files.writeString(directory.resolve("compose.yaml"), "services:\n  app:\n    image: ${" + variable + "}\n");
        String processValue = System.getenv(variable);

        var project = new ComposeFileMerger().mergeWithEnv(directory, List.of("compose.yaml"),
                Map.of(variable, "from-override"));

        assertEquals("from-override", project.services().get("app").image());
        assertEquals(processValue, System.getenv(variable));
    }

    @Test
    void matchingOverrideUsesPlatformEnvironmentCaseRules() throws Exception {
        String variable = "JCOMPOSE_CASE_OVERRIDE_" + Long.toUnsignedString(System.nanoTime());
        Files.writeString(directory.resolve("compose.yaml"), "services:\n  app:\n    image: ${" + variable + ":-fallback}\n");
        Map<String, String> overrides = Map.of(variable.toUpperCase(java.util.Locale.ROOT), "override");

        var project = new ComposeFileMerger().mergeWithEnv(directory, List.of("compose.yaml"), overrides);

        String expected = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                ? "override" : "fallback";
        assertEquals(expected, project.services().get("app").image());
    }

    @Test
    void lastMatchingOverrideWins() throws Exception {
        String variable = "JCOMPOSE_OVERRIDE_ORDER_" + Long.toUnsignedString(System.nanoTime());
        Files.writeString(directory.resolve("compose.yaml"), "services:\n  app:\n    image: ${" + variable + "}\n");
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put(variable.toUpperCase(java.util.Locale.ROOT), "first");
        overrides.put(variable, "second");

        var project = new ComposeFileMerger().mergeWithEnv(directory, List.of("compose.yaml"), overrides);

        assertEquals("second", project.services().get("app").image());
    }

    @Test
    void emptyOverrideTakesPrecedenceOverDotEnv() throws Exception {
        String variable = "JCOMPOSE_EMPTY_OVERRIDE_" + Long.toUnsignedString(System.nanoTime());
        Files.writeString(directory.resolve(".env"), variable + "=from-dotenv\n");
        Files.writeString(directory.resolve("compose.yaml"), "services:\n  app:\n    image: alpine\n    labels:\n      value: \"${" + variable + "}\"\n");

        var project = new ComposeFileMerger().mergeWithEnv(directory, List.of("compose.yaml"), Map.of(variable, ""));

        assertEquals("", ((Map<?, ?>) project.services().get("app").configuration().get("labels")).get("value"));
    }

    @Test
    void concurrentOverridesRemainIsolated() throws Exception {
        String variable = "JCOMPOSE_CONCURRENT_OVERRIDE_" + Long.toUnsignedString(System.nanoTime());
        Files.writeString(directory.resolve(".env"), variable + "=from-dotenv\n");
        Files.writeString(directory.resolve("compose.yaml"), "services:\n  app:\n    image: ${" + variable + "}\n");
        String processValue = System.getenv(variable);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> mergeAfterBarrier(variable, "first", ready, start));
            Future<String> second = executor.submit(() -> mergeAfterBarrier(variable, "second", ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(processValue, System.getenv(variable));
    }

    private String mergeAfterBarrier(String variable, String value, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        var project = new ComposeFileMerger().mergeWithEnv(directory, List.of("compose.yaml"), Map.of(variable, value));
        return project.services().get("app").image();
    }
}
