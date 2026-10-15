package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import io.github.gattgeng.jcompose.api.ComposeProjectContext;

import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.time.Duration;

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
