package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.Base64;
import java.time.Duration;
import java.time.Instant;

/** Creates Docker Engine clients from per-project daemon and registry settings. */
public final class DockerClientFactory {
    private DockerClientFactory() { }

    public static DockerClient create(ComposeProjectContext context) {
        return create(context, List.of());
    }

    static DockerClient create(ComposeProjectContext context, List<String> commitChanges) {
        var builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
        String endpoint = normalizeEndpoint(context == null ? null : context.socketPath());
        if (endpoint != null) builder.withDockerHost(endpoint);
        if (context != null && context.registryAuth() != null) {
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
        if (commitChanges != null && !commitChanges.isEmpty())
            transport = new CommitChangesHttpClient(transport, commitChanges);
        return DockerClientImpl.getInstance(config, transport);
    }

    static Instant imageLastTagTime(DockerClient client, String imageReference) {
        if (!(client instanceof DockerClientImpl dockerClient))
            throw new IllegalArgumentException("Docker client does not expose Docker Engine image metadata.");
        var request = DockerHttpClient.Request.builder().method("GET")
                .path("/v1.44/images/" + imageReference + "/json").build();
        try (var response = dockerClient.getHttpClient().execute(request)) {
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300)
                throw new IllegalStateException("Could not read Docker image metadata (HTTP " + response.getStatusCode() + ").");
            JsonNode image = DockerClientConfig.getDefaultObjectMapper().readTree(response.getBody());
            String lastTagTime = image.path("Metadata").path("LastTagTime").asText();
            if (lastTagTime.isBlank() || lastTagTime.startsWith("0001-01-01")) return null;
            return Instant.parse(lastTagTime);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException("Could not read Docker image metadata.", exception);
        }
    }

    static ContainerArchivePathStat containerArchivePathStat(DockerClient client, String containerId, String path) {
        if (!(client instanceof DockerClientImpl dockerClient))
            throw new IllegalArgumentException("Docker client does not expose Docker Engine archive metadata.");
        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20");
        var request = DockerHttpClient.Request.builder().method("HEAD")
                .path("/v1.44/containers/" + containerId + "/archive?path=" + encodedPath).build();
        try (var response = dockerClient.getHttpClient().execute(request)) {
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300)
                throw new IllegalStateException("Could not read Docker container path metadata (HTTP " + response.getStatusCode() + ").");
            String stat = response.getHeader("X-Docker-Container-Path-Stat");
            if (stat == null || stat.isBlank())
                throw new IllegalStateException("Docker Engine did not return container path metadata.");
            return parseContainerArchivePathStat(stat);
        }
    }

    static ContainerArchivePathStat parseContainerArchivePathStat(String encodedStat) {
        try {
            byte[] json = Base64.getDecoder().decode(encodedStat);
            JsonNode node = DockerClientConfig.getDefaultObjectMapper().readTree(json);
            if (node == null || !node.isObject() || !node.path("name").isTextual()
                    || !node.path("mode").isIntegralNumber()
                    || !node.path("linkTarget").isTextual())
                throw new IllegalArgumentException("Docker returned incomplete container path metadata.");
            return new ContainerArchivePathStat(node.path("name").asText(), node.path("mode").asLong(), node.path("linkTarget").asText());
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Docker returned invalid container path metadata.", exception);
        }
    }

    record ContainerArchivePathStat(String name, long mode, String linkTarget) {
        private static final long MODE_SYMLINK = 1L << 27; // Docker serializes Go os.FileMode, not POSIX mode bits.
        private static final long MODE_DIRECTORY = 1L << 31;
        boolean symbolicLink() { return (mode & MODE_SYMLINK) != 0; }
        boolean directory() { return (mode & MODE_DIRECTORY) != 0; }
    }

    static String appendCommitChanges(String path, List<String> changes) {
        if (changes == null || changes.isEmpty() || !path.contains("/commit?")) return path;
        StringBuilder result = new StringBuilder(path);
        for (String change : changes) {
            result.append('&').append("changes=")
                    .append(URLEncoder.encode(change, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return result.toString();
    }

    private static final class CommitChangesHttpClient implements DockerHttpClient {
        private final DockerHttpClient delegate;
        private final List<String> changes;

        private CommitChangesHttpClient(DockerHttpClient delegate, List<String> changes) {
            this.delegate = delegate;
            this.changes = List.copyOf(changes);
        }

        @Override public Response execute(Request request) {
            String path = appendCommitChanges(request.path(), changes);
            return delegate.execute(Request.builder().from(request).path(path).build());
        }

        @Override public void close() throws IOException { delegate.close(); }
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
