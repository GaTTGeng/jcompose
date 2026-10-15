package io.github.gattgeng.jcompose.api;

/** One reported build-context change for a selected service. */
public record WatchEvent(String serviceName, String action, String path) { }
