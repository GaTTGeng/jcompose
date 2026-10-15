package io.github.gattgeng.jcompose.api;

import java.util.Map;

/** Structured outcome for an engine operation. */
public record OperationResult(String service, String resourceId, boolean successful, String message,
                              Map<String, Object> details) {
    public OperationResult { details = details == null ? Map.of() : Map.copyOf(details); }
}
