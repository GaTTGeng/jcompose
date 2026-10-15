package io.github.gattgeng.jcompose.api;

import java.util.Map;

/** Extensible options bag; operation-specific keys follow the ComposeSharp 3.0 contract. */
public record ComposeOperationOptions(Map<String, Object> values) {
    public ComposeOperationOptions { values = values == null ? Map.of() : Map.copyOf(values); }
    public static ComposeOperationOptions defaults() { return new ComposeOperationOptions(Map.of()); }
    public Object get(String name) { return values.get(name); }
}
