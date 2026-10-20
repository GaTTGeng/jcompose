package io.github.gattgeng.jcompose.api;

/**
 * A port mapping declared by a Compose service.
 * @param target container port or target range
 * @param published published host port or range, or {@code null} when omitted
 * @param hostIp host IP, or {@code null} when omitted
 * @param protocol transport protocol; defaults to {@code tcp}
 * @param mode publishing mode from long syntax
 * @param name port name from long syntax
 * @param appProtocol application protocol from long syntax
 */
public record ComposeServicePort(
        String target,
        String published,
        String hostIp,
        String protocol,
        String mode,
        String name,
        String appProtocol) {
}
