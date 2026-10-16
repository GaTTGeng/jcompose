package io.github.gattgeng.jcompose.engine;

import io.github.gattgeng.jcompose.api.ComposePortOptions;
import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.api.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposePortProtocolTest {
    @Test
    void rejectsProtocolsNotSupportedByDockerComposePort() {
        CompletionException exception = assertThrows(CompletionException.class, () ->
                new ComposeService().portAsync(
                        new ComposeProjectContext("test", Path.of(".")),
                        "app", 8080, ComposePortOptions.builder().protocol("sctp").build()).join());

        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }

    @Test
    void expandsShortPortRangesIntoDockerBindings() {
        ServiceDefinition service = service(Map.of("ports", List.of("9090-9091:8080-8081/udp")));

        List<com.github.dockerjava.api.model.PortBinding> ports = ComposeService.servicePorts(service);

        assertEquals(List.of("8080/udp=9090", "8081/udp=9091"), ports.stream()
                .map(binding -> binding.getExposedPort() + "=" + binding.getBinding().getHostPortSpec()).toList());
    }

    @Test
    void parsesBracketedIpv6HostAddressWithPortRange() {
        ServiceDefinition service = service(Map.of("ports", List.of("[::1]:9090-9091:8080-8081")));

        List<com.github.dockerjava.api.model.PortBinding> ports = ComposeService.servicePorts(service);

        assertEquals(List.of("8080/tcp=::1:9090", "8081/tcp=::1:9091"), ports.stream()
                .map(binding -> binding.getExposedPort() + "=" + binding.getBinding()).toList());
    }

    @Test
    void expandsLongTargetRangesAndKeepsPublishedRangesForSingleTarget() {
        ServiceDefinition service = service(Map.of("ports", List.of(
                Map.of("target", "8080-8081", "published", "9090-9091", "protocol", "udp", "host_ip", "127.0.0.1"),
                Map.of("target", 443, "published", "8443-9000", "protocol", "tcp"))));

        List<com.github.dockerjava.api.model.PortBinding> ports = ComposeService.servicePorts(service);

        assertEquals(List.of("8080/udp=127.0.0.1:9090", "8081/udp=127.0.0.1:9091", "443/tcp=8443-9000"),
                ports.stream().map(binding -> binding.getExposedPort() + "=" + binding.getBinding()).toList());
    }

    @Test
    void acceptsZeroAsAnEphemeralPublishedPort() {
        ServiceDefinition service = service(Map.of("ports", List.of("0:8080", Map.of("target", 8081, "published", "0"))));

        List<com.github.dockerjava.api.model.PortBinding> ports = ComposeService.servicePorts(service);

        assertEquals(List.of("0", "0"), ports.stream().map(binding -> binding.getBinding().getHostPortSpec()).toList());
    }

    @Test
    void expandsExposedPortRanges() {
        ServiceDefinition service = service(Map.of("expose", List.of("8080-8082/udp")));

        assertEquals(List.of("8080/udp", "8081/udp", "8082/udp"), ComposeService.serviceExposedPorts(service).stream()
                .map(Object::toString).sorted().toList());
    }

    @Test
    void rejectsMismatchedPublishedAndTargetRanges() {
        ServiceDefinition service = service(Map.of("ports", List.of("9090-9092:8080-8081")));

        assertThrows(IllegalArgumentException.class, () -> ComposeService.servicePorts(service));
    }

    @Test
    void rejectsSinglePublishedPortForTargetRange() {
        ServiceDefinition service = service(Map.of("ports", List.of("9090:8080-8081")));

        assertThrows(IllegalArgumentException.class, () -> ComposeService.servicePorts(service));
    }

    private static ServiceDefinition service(Map<String, Object> configuration) {
        return new ServiceDefinition("app", configuration);
    }
}
