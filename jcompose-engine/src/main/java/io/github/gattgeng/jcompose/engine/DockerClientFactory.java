package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import io.github.gattgeng.jcompose.api.ComposeProjectContext;

import java.nio.file.Path;
import java.time.Duration;

/** Creates Docker Engine clients from per-project daemon and registry settings. */
public final class DockerClientFactory {
    private DockerClientFactory() { }

    public static DockerClient create(ComposeProjectContext context) {
        var builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
        String endpoint = normalizeEndpoint(context.socketPath());
        if (endpoint != null) builder.withDockerHost(endpoint);
        if (context.registryAuth() != null) {
            var auth = context.registryAuth();
            if (auth.username() != null) builder.withRegistryUsername(auth.username());
            if (auth.password() != null) builder.withRegistryPassword(auth.password());
            if (auth.email() != null) builder.withRegistryEmail(auth.email());
            if (auth.serverAddress() != null) builder.withRegistryUrl(auth.serverAddress());
        }
        DockerClientConfig config = builder.build();
        DockerHttpClient transport = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();
        return DockerClientImpl.getInstance(config, transport);
    }

    private static String normalizeEndpoint(String value) {
        if (value == null || value.isBlank()) return null;
        String endpoint = value.trim();
        if (endpoint.contains("://")) return endpoint;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            String pipe = endpoint.replace('\\', '/');
            if (pipe.startsWith("//./pipe/")) pipe = pipe.substring(9);
            else if (pipe.startsWith("./pipe/")) pipe = pipe.substring(7);
            else if (pipe.startsWith("pipe/")) pipe = pipe.substring(5);
            return "npipe:////./pipe/" + pipe;
        }
        return "unix://" + Path.of(endpoint).toAbsolutePath().normalize();
    }
}
