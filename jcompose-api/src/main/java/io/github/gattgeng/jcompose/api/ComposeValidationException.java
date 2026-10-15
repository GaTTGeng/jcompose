package io.github.gattgeng.jcompose.api;

import java.nio.file.Path;

/** Invalid Compose input with source and property context where available. */
public final class ComposeValidationException extends RuntimeException {
    private final Path sourceFile;
    private final String propertyPath;
    private final String serviceName;
    private final Integer line;
    private final Integer column;

    public ComposeValidationException(Path sourceFile, String propertyPath, String serviceName,
                                      String detail, Integer line, Integer column, Throwable cause) {
        super(format(sourceFile, propertyPath, serviceName, detail, line, column), cause);
        this.sourceFile = sourceFile;
        this.propertyPath = propertyPath;
        this.serviceName = serviceName;
        this.line = line;
        this.column = column;
    }
    public ComposeValidationException(Path sourceFile, String propertyPath, String detail) {
        this(sourceFile, propertyPath, null, detail, null, null, null);
    }
    private static String format(Path file, String path, String service, String detail, Integer line, Integer column) {
        return "Invalid Compose input in '" + file + "'" + (path == null ? "" : " at '" + path + "'")
                + (service == null ? "" : ", service '" + service + "'")
                + (line == null ? "" : " (" + line + ":" + column + ")") + ": " + detail;
    }
    public Path sourceFile() { return sourceFile; }
    public String propertyPath() { return propertyPath; }
    public String serviceName() { return serviceName; }
    public Integer line() { return line; }
    public Integer column() { return column; }
}
