package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.WatchEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildContextWatcherTest {
    @TempDir
    Path directory;

    @Test
    void emptyRegistrationsCompleteImmediately() throws Exception {
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, 0);

        new BuildContextWatcher(List.of()).subscribe(subscriber);

        assertTrue(subscriber.completed.await(5, TimeUnit.SECONDS));
        assertTrue(subscriber.events.isEmpty());
    }

    @Test
    void sharedContextChangesFanOutInServiceOrderAndWatchNewDirectories() throws Exception {
        Path context = Files.createDirectory(directory.resolve("shared"));
        CountDownLatch registered = new CountDownLatch(1);
        BuildContextWatcher watcher = new BuildContextWatcher(List.of(
                new BuildContextWatcher.Registration("zeta", context),
                new BuildContextWatcher.Registration("alpha", context)), registered::countDown);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, 0);
        watcher.subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS));
        assertTrue(registered.await(5, TimeUnit.SECONDS));
        Path changed = Files.createDirectory(context.resolve("nested"));
        List<WatchEvent> directoryEvents = awaitPathEvents(subscriber, changed, 2);
        assertEquals(List.of("alpha", "zeta"), directoryEvents.stream().map(WatchEvent::serviceName).toList());
        assertTrue(directoryEvents.stream().allMatch(event -> "rebuild".equals(event.action())));

        Path nestedFile = Files.createFile(changed.resolve("file.txt"));
        List<WatchEvent> nestedEvents = awaitPathEvents(subscriber, nestedFile, 2);
        assertEquals(List.of("alpha", "zeta"), nestedEvents.stream().map(WatchEvent::serviceName).toList());
        subscriber.subscription.get().cancel();
    }

    @Test
    void cancellationStopsTheSubscription() throws Exception {
        BuildContextWatcher watcher = new BuildContextWatcher(List.of(
                new BuildContextWatcher.Registration("app", directory)));
        RecordingSubscriber subscriber = new RecordingSubscriber(1, 1);
        watcher.subscribe(subscriber);
        assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS));
        subscriber.subscription.get().cancel();

        Files.createDirectory(directory.resolve("after-cancel"));
        assertFalse(subscriber.eventsReady.await(200, TimeUnit.MILLISECONDS));
        assertEquals(0, subscriber.events.size());
    }

    @Test
    void missingContextNamesTheServiceAndPath() {
        Path missing = directory.resolve("missing");

        IllegalArgumentException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new BuildContextWatcher(List.of(new BuildContextWatcher.Registration("broken", missing))));

        assertTrue(exception.getMessage().contains("broken"));
        assertTrue(exception.getMessage().contains(missing.toAbsolutePath().normalize().toString()));
    }

    @Test
    void appliesActionAndPathFilterBeforeReportingAChange() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        List<WatchEvent> changes = java.util.Collections.synchronizedList(new ArrayList<>());
        BuildContextWatcher watcher = new BuildContextWatcher(List.of(new BuildContextWatcher.Registration(
                "app", directory, "sync", path -> path.toString().endsWith(".java"))),
                registered::countDown, changes::add);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, 1);
        watcher.subscribe(subscriber);
        assertTrue(registered.await(5, TimeUnit.SECONDS));
        Path ignored = Files.createFile(directory.resolve("notes.txt"));
        Path included = Files.createFile(directory.resolve("Main.java"));

        List<WatchEvent> events = awaitPathEvents(subscriber, included, 1);
        assertTrue(events.stream().allMatch(event -> "sync".equals(event.action())));
        assertFalse(subscriber.snapshot().stream().anyMatch(event -> event.path().equals(ignored.toAbsolutePath().normalize().toString())));
        assertEquals(events, List.copyOf(changes));
        Files.delete(included);
        List<WatchEvent> deletionEvents = awaitPathEvents(subscriber, included, 2);
        assertEquals(2, deletionEvents.size());
        assertEquals(deletionEvents, List.copyOf(changes));
        subscriber.subscription.get().cancel();
    }

    @Test
    void filtersEditorTemporaryFilesAndCoalescesRepeatedNotifications() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        BuildContextWatcher watcher = new BuildContextWatcher(List.of(
                new BuildContextWatcher.Registration("app", directory)), registered::countDown);
        RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, 0);
        watcher.subscribe(subscriber);
        assertTrue(registered.await(5, TimeUnit.SECONDS));

        Path ignored = Files.createFile(directory.resolve("Main.java___jb_tmp___"));
        Path changed = directory.resolve("Main.java");
        Files.writeString(changed, "one");
        Files.writeString(changed, "two");
        assertFalse(awaitPathEvents(subscriber, changed, 1).isEmpty());
        Thread.sleep(700);

        assertFalse(subscriber.snapshot().stream().anyMatch(event -> event.path().equals(ignored.toString())));
        assertEquals(1, subscriber.snapshot().stream().filter(event -> event.path().equals(changed.toString())).count());
        subscriber.subscription.get().cancel();
    }

    @Test
    void initialSyncCollectsOnlyIncludedNonIgnoredFiles() throws Exception {
        Files.createDirectories(directory.resolve("src/generated"));
        Files.createDirectories(directory.resolve(".git"));
        Files.createDirectories(directory.resolve(".idea"));
        Path main = Files.createFile(directory.resolve("Main.java"));
        Path nested = Files.createFile(directory.resolve("src/App.java"));
        Files.createFile(directory.resolve("src/generated/Build.java"));
        Path gitFile = Files.createFile(directory.resolve(".git/config"));
        Files.createFile(directory.resolve("README.md"));
        Files.createFile(directory.resolve(".idea/workspace.xml"));
        Files.createFile(directory.resolve("Main.java~"));
        Files.writeString(directory.resolve(".dockerignore"), "src/App.java\n");
        Map<String, Object> rule = Map.of("include", List.of("**/*.java", "*.java"), "ignore", List.of("src/generated/**"));

        List<Path> files = ComposeService.collectWatchFiles(directory, rule,
                DockerBuildContextArchive.watchIgnoreMatcher(directory, directory.resolve("Dockerfile")));

        assertEquals(List.of(main), files);
        assertFalse(files.contains(gitFile));
        assertFalse(files.stream().anyMatch(file -> file.toString().contains(".idea") || file.toString().endsWith("~")));
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<WatchEvent> {
        private final long initialDemand;
        private final List<WatchEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final CountDownLatch eventsReady;
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private RecordingSubscriber(long initialDemand) {
            this(initialDemand, initialDemand > Integer.MAX_VALUE ? 0 : (int) initialDemand);
        }

        private RecordingSubscriber(long initialDemand, int eventCount) {
            this.initialDemand = initialDemand;
            this.eventsReady = new CountDownLatch(eventCount);
        }

        private List<WatchEvent> snapshot() {
            synchronized (events) { return List.copyOf(events); }
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription.set(value);
            subscribed.countDown();
            if (initialDemand > 0) value.request(initialDemand);
        }

        @Override
        public void onNext(WatchEvent item) {
            events.add(item);
            eventsReady.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }
    }

    private static List<WatchEvent> awaitPathEvents(RecordingSubscriber subscriber, Path path, int count) throws InterruptedException {
        String expected = path.toAbsolutePath().normalize().toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<WatchEvent> matching;
        do {
            matching = subscriber.snapshot().stream().filter(event -> expected.equals(event.path())).toList();
            if (matching.size() >= count) return matching;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out waiting for " + count + " events for " + path + "; watcher failure: " + subscriber.failure.get());
    }
}
