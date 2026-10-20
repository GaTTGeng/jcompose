package io.github.gattgeng.jcompose.api;

/** Value-free description of a Compose field that is not fully applied by jcompose. */
public record ComposeDiagnostic(String sourceFile, String propertyPath, String serviceName,
                                String code, String message) { }
