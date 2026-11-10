package io.github.gattgeng.jcompose.api;

import java.util.Map;

/** Typed projection of a service logging configuration.
 * @param driver Docker logging driver name.
 * @param options Driver-specific string options.
 */
public record LoggingConfig(String driver, Map<String, String> options) {
    public LoggingConfig {
        options = options == null ? null : Map.copyOf(options);
    }
}
