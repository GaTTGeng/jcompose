package io.github.gattgeng.jcompose.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.dockerjava.api.model.ContainerNetwork;

import java.util.List;
import java.util.Map;

/** Docker Engine endpoint fields that docker-java 3.7.1 does not model yet. */
final class ComposeEndpointConfig extends ContainerNetwork {
    @JsonProperty("InterfaceName")
    private String interfaceName;

    @JsonProperty("DriverOpts")
    private Map<String, String> driverOpts;

    @JsonProperty("GwPriority")
    private Integer gwPriority;

    @Override
    public ComposeEndpointIpam getIpamConfig() {
        return (ComposeEndpointIpam) super.getIpamConfig();
    }

    ComposeEndpointConfig withInterfaceName(String value) {
        interfaceName = value;
        return this;
    }

    ComposeEndpointConfig withDriverOpts(Map<String, String> value) {
        driverOpts = value;
        return this;
    }

    ComposeEndpointConfig withGwPriority(Integer value) {
        gwPriority = value;
        return this;
    }

    ComposeEndpointConfig withComposeIpamConfig(ComposeEndpointIpam value) {
        super.withIpamConfig(value);
        return this;
    }

    static final class ComposeEndpointIpam extends ContainerNetwork.Ipam {
        @JsonProperty("LinkLocalIPs")
        private List<String> linkLocalIps;

        @Override
        public ComposeEndpointIpam withIpv4Address(String value) {
            super.withIpv4Address(value);
            return this;
        }

        @Override
        public ComposeEndpointIpam withIpv6Address(String value) {
            super.withIpv6Address(value);
            return this;
        }

        ComposeEndpointIpam withLinkLocalIps(List<String> value) {
            linkLocalIps = value;
            return this;
        }
    }
}
