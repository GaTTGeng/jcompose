package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposePullPolicyTest {
    @Test
    void parsesComposeTimeBasedRefreshPolicies() {
        assertEquals(Duration.ofDays(1), ComposeService.pullRefreshDuration("daily", null));
        assertEquals(Duration.ofDays(7), ComposeService.pullRefreshDuration("weekly", null));
        assertEquals(Duration.ofHours(26), ComposeService.pullRefreshDuration("every_1d2h", null));
        assertEquals(Duration.ofMinutes(90), ComposeService.pullRefreshDuration("every_1h30m", null));
        assertEquals(Duration.ofHours(4), ComposeService.pullRefreshDuration("refresh", "4h"));
    }

    @Test
    void onlyRecognizesSupportedComposeTimePolicies() {
        org.junit.jupiter.api.Assertions.assertTrue(ComposeService.isTimeBasedPullPolicy("daily"));
        org.junit.jupiter.api.Assertions.assertTrue(ComposeService.isTimeBasedPullPolicy("weekly"));
        org.junit.jupiter.api.Assertions.assertTrue(ComposeService.isTimeBasedPullPolicy("every_2w3d"));
        org.junit.jupiter.api.Assertions.assertFalse(ComposeService.isTimeBasedPullPolicy("every_2x"));
        assertThrows(IllegalArgumentException.class,
                () -> ComposeService.pullRefreshDuration("every_1x", null));
    }

    @Test
    void readsLastTagTimeFromDockerEngineImageMetadata() throws Exception {
        String timestamp = "2026-10-15T11:30:00.123456789Z";
        String responseBody = "{\"Metadata\":{\"LastTagTime\":\"" + timestamp + "\"}}";
        final String[] requestedPath = new String[1];
        DockerHttpClient transport = new DockerHttpClient() {
            @Override public Response execute(Request request) {
                requestedPath[0] = request.path();
                return new Response() {
                    @Override public int getStatusCode() { return 200; }
                    @Override public Map<String, List<String>> getHeaders() { return Map.of(); }
                    @Override public java.io.InputStream getBody() {
                        return new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
                    }
                    @Override public void close() { }
                };
            }
            @Override public void close() throws IOException { }
        };
        try (DockerClientImpl client = (DockerClientImpl) DockerClientImpl.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().build(), transport)) {
            assertEquals(Instant.parse(timestamp), DockerClientFactory.imageLastTagTime(client, "example/app:latest"));
        }
        assertEquals("/v1.44/images/example/app:latest/json", requestedPath[0]);
    }
}
