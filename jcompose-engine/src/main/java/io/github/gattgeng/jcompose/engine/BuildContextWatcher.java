package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.WatchEvent;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Watches selected local build contexts and reports absolute changed paths. */
final class BuildContextWatcher implements Flow.Publisher<WatchEvent> {
    record Registration(String service, Path directory, String action, Predicate<Path> includes) {
        Registration(String service, Path directory) { this(service, directory, "rebuild", path -> true); }
    }

    private final Map<Path, List<Registration>> contexts;
    private final Runnable onRegistered;
    private final Consumer<WatchEvent> onChange;

    BuildContextWatcher(List<Registration> registrations) {
        this(registrations, () -> { });
    }

    BuildContextWatcher(List<Registration> registrations, Runnable onRegistered) {
        this(registrations, onRegistered, ignored -> { });
    }

    BuildContextWatcher(List<Registration> registrations, Runnable onRegistered, Consumer<WatchEvent> onChange) {
        Map<Path, List<Registration>> grouped = new LinkedHashMap<>();
        for (Registration registration : registrations) {
            if (registration.service() == null || registration.service().isBlank())
                throw new IllegalArgumentException("Build context service name must not be blank.");
            if (registration.directory() == null)
                throw new IllegalArgumentException("Build context directory must not be null.");
            Path directory = registration.directory().toAbsolutePath().normalize();
            if (!Files.isDirectory(directory))
                throw new IllegalArgumentException("Build context for service '" + registration.service() + "' does not exist: " + directory);
            List<Registration> rules = grouped.computeIfAbsent(directory, ignored -> new ArrayList<>());
            Registration normalized = new Registration(registration.service(), directory, registration.action(), registration.includes());
            if (!rules.contains(normalized)) rules.add(normalized);
        }
        grouped.values().forEach(rules -> rules.sort(java.util.Comparator.comparing(Registration::service)
                .thenComparing(Registration::action)));
        this.contexts = Map.copyOf(grouped);
        this.onRegistered = java.util.Objects.requireNonNull(onRegistered, "onRegistered");
        this.onChange = java.util.Objects.requireNonNull(onChange, "onChange");
    }

    @Override public void subscribe(Flow.Subscriber<? super WatchEvent> subscriber) {
        java.util.Objects.requireNonNull(subscriber, "subscriber");
        WatchSubscription subscription = new WatchSubscription(subscriber);
        subscriber.onSubscribe(subscription);
    }

    private final class WatchSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super WatchEvent> subscriber;
        private final LinkedBlockingQueue<WatchEvent> events = new LinkedBlockingQueue<>(1024);
        private final Object lock = new Object();
        private long demand;
        private boolean started;
        private boolean cancelled;
        private boolean complete;
        private Throwable failure;
        private volatile WatchService watcher;
        private volatile Thread watcherThread;
        private volatile Thread drainThread;

        private WatchSubscription(Flow.Subscriber<? super WatchEvent> subscriber) {
            this.subscriber = subscriber;
        }

        @Override public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("Flow.Subscription.request requires a positive count."));
                return;
            }
            synchronized (lock) {
                if (cancelled || complete || failure != null) return;
                demand = demand > Long.MAX_VALUE - count ? Long.MAX_VALUE : demand + count;
                if (!started) {
                    started = true;
                    if (contexts.isEmpty()) complete = true;
                    else {
                        watcherThread = Thread.ofVirtual().name("jcompose-watch-files").start(this::watchFiles);
                    }
                    drainThread = Thread.ofVirtual().name("jcompose-watch-drain").start(this::drain);
                }
                lock.notifyAll();
            }
        }

        @Override public void cancel() {
            synchronized (lock) {
                if (cancelled) return;
                cancelled = true;
                lock.notifyAll();
            }
            closeWatcher();
            Thread drain = drainThread;
            if (drain != null) drain.interrupt();
        }

        private void watchFiles() {
            try (WatchService activeWatcher = FileSystems.getDefault().newWatchService()) {
                watcher = activeWatcher;
                Map<WatchKey, DirectoryRegistration> keys = new java.util.concurrent.ConcurrentHashMap<>();
                for (Map.Entry<Path, List<Registration>> context : contexts.entrySet()) registerTree(activeWatcher, keys, context.getKey(), context.getValue());
                onRegistered.run();
                while (!isCancelled()) {
                    WatchKey key = activeWatcher.take();
                    DirectoryRegistration registration = keys.get(key);
                    if (registration == null) {
                        key.reset();
                        continue;
                    }
                    for (java.nio.file.WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW)
                            throw new IOException("Build context watcher event buffer overflowed for '" + registration.root + "'.");
                        Object context = event.context();
                        if (!(context instanceof Path keyRelative)) continue;
                        Path changed = registration.directory.resolve(keyRelative).toAbsolutePath().normalize();
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed))
                            registerTree(activeWatcher, keys, registration.root, registration.rules, changed);
                        for (Registration rule : registration.rules) {
                            Path relative = rule.directory().relativize(changed);
                            if (!rule.includes().test(relative)) continue;
                            WatchEvent change = new WatchEvent(rule.service(), rule.action(), changed.toString());
                            onChange.accept(change);
                            if (!events.offer(change))
                                throw new IOException("Build context watcher event buffer overflowed for '" + registration.root + "'.");
                        }
                    }
                    if (!key.reset()) keys.remove(key);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!isCancelled()) fail(exception);
            } catch (java.nio.file.ClosedWatchServiceException ignored) {
                if (!isCancelled()) fail(new IOException("Build context watcher closed unexpectedly."));
            } catch (Throwable throwable) {
                if (!isCancelled()) fail(throwable);
            } finally {
                synchronized (lock) {
                    if (!cancelled && failure == null) complete = true;
                    lock.notifyAll();
                }
            }
        }

        private void registerTree(WatchService activeWatcher, Map<WatchKey, DirectoryRegistration> keys,
                                  Path root, List<Registration> rules) throws IOException {
            registerTree(activeWatcher, keys, root, rules, root);
        }

        private void registerTree(WatchService activeWatcher, Map<WatchKey, DirectoryRegistration> keys,
                                  Path root, List<Registration> rules, Path start) throws IOException {
            try (var paths = Files.walk(start)) {
                for (Path directory : paths.filter(Files::isDirectory).toList()) {
                    WatchKey key = directory.register(activeWatcher,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY);
                    keys.put(key, new DirectoryRegistration(root, directory, rules));
                }
            }
        }

        private void drain() {
            while (true) {
                WatchEvent next = null;
                Throwable terminalError = null;
                boolean terminalComplete = false;
                synchronized (lock) {
                    while (!cancelled && demand == 0 && failure == null && !complete) {
                        try { lock.wait(); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                    }
                    if (cancelled) return;
                    if (failure != null) terminalError = failure;
                    else if (demand > 0) {
                        next = events.poll();
                        if (next != null && demand != Long.MAX_VALUE) demand--;
                        else if (complete && events.isEmpty()) terminalComplete = true;
                        else {
                            try { lock.wait(100); }
                            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                        }
                    } else if (complete && events.isEmpty()) terminalComplete = true;
                }
                if (terminalError != null) {
                    cancel();
                    subscriber.onError(terminalError);
                    return;
                }
                if (terminalComplete) {
                    cancel();
                    subscriber.onComplete();
                    return;
                }
                if (next != null) {
                    try { subscriber.onNext(next); }
                    catch (Throwable throwable) { cancel(); return; }
                }
            }
        }

        private void fail(Throwable throwable) {
            synchronized (lock) {
                if (cancelled || complete || failure != null) return;
                failure = throwable;
                lock.notifyAll();
            }
            closeWatcher();
        }

        private boolean isCancelled() {
            synchronized (lock) { return cancelled; }
        }

        private void closeWatcher() {
            WatchService active = watcher;
            if (active != null) try { active.close(); } catch (IOException ignored) { }
            Thread producer = watcherThread;
            if (producer != null && producer != Thread.currentThread()) producer.interrupt();
        }
    }

    private record DirectoryRegistration(Path root, Path directory, List<Registration> rules) { }
}
