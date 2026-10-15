package io.github.gattgeng.jcompose.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exit codes collected after waiting for project containers to exit. */
public record WaitResult(Map<String, Integer> exitCodes, int code) {
    public WaitResult {
        exitCodes = exitCodes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(exitCodes));
    }
}
