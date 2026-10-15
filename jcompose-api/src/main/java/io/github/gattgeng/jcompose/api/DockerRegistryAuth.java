package io.github.gattgeng.jcompose.api;

/** Registry credentials supplied by local configuration. */
public record DockerRegistryAuth(String username, String password, String email, String serverAddress) { }
