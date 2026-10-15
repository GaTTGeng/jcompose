package io.github.gattgeng.jcompose.api;

/** One Compose project discovered on the Docker daemon. */
public record Stack(String id, String name, String status, String configFiles, String reason) { }
