package io.github.gattgeng.jcompose.api;

/** One mount attached to a container. */
public record MountInfo(String type, String source, String destination, String mode, boolean readWrite) { }
