package io.github.gattgeng.jcompose.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.dockerjava.api.model.Network;

import java.util.Map;

/** Adds Docker Engine IPAM fields that docker-java 3.7.1 does not expose. */
final class ComposeNetworkIpam extends Network.Ipam {
    private final Map<String, String> composeOptions;

    ComposeNetworkIpam(Map<String, String> options) {
        this.composeOptions = options;
    }

    @Override
    @JsonProperty("Options")
    public Map<String, String> getOptions() {
        return composeOptions;
    }

    static final class Config extends Network.Ipam.Config {
        private final Map<String, String> auxiliaryAddresses;

        Config(Map<String, String> auxiliaryAddresses) {
            this.auxiliaryAddresses = auxiliaryAddresses;
        }

        @JsonProperty("AuxiliaryAddresses")
        public Map<String, String> getAuxiliaryAddresses() {
            return auxiliaryAddresses;
        }
    }
}
