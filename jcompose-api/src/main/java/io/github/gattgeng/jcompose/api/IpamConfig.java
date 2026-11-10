package io.github.gattgeng.jcompose.api;

import java.util.List;

/** Typed network IPAM driver and subnet configuration. */
public record IpamConfig(String driver, List<IpamSubnetConfig> config) {
    public IpamConfig {
        config = config == null ? null : List.copyOf(config);
    }
}
