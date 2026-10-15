package io.github.gattgeng.jcompose.api;

/** One published port mapping of a container. */
public record PortPublisher(String url, int targetPort, int publishedPort, String protocol, String hostIp) {
    public PortPublisher {
        protocol = protocol == null || protocol.isBlank() ? "tcp" : protocol;
    }
}
