package io.github.gattgeng.jcompose.api;

/** One subnet entry in a network IPAM configuration. */
public record IpamSubnetConfig(String subnet, String gateway, String ipRange) { }
