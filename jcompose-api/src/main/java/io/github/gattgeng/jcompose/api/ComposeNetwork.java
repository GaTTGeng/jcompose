package io.github.gattgeng.jcompose.api;

import java.util.Map;

/** Typed projection of a top-level Compose network definition. */
public record ComposeNetwork(String name, boolean external, String driver, Boolean internal,
                             Boolean attachable, Map<String, String> labels, Map<String, String> driverOpts,
                             IpamConfig ipam, Boolean enableIpv6, String scope) {
    public ComposeNetwork {
        labels = labels == null ? null : Map.copyOf(labels);
        driverOpts = driverOpts == null ? null : Map.copyOf(driverOpts);
    }
}
