package io.github.gattgeng.jcompose.api;

import java.util.List;

/** Replica status of one service after a scaling or listing operation. */
public record ServiceStatus(String serviceName, int desired, int running,
                            List<String> ports, List<PortPublisher> publishers) {
    public ServiceStatus {
        ports = ports == null ? List.of() : List.copyOf(ports);
        publishers = publishers == null ? List.of() : List.copyOf(publishers);
    }
}
