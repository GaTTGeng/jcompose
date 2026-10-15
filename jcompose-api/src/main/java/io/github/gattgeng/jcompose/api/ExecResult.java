package io.github.gattgeng.jcompose.api;

/** Outcome of executing a command inside a service container. */
public record ExecResult(int exitCode, String standardOutput, String standardError) {
    public ExecResult {
        standardOutput = standardOutput == null ? "" : standardOutput;
        standardError = standardError == null ? "" : standardError;
    }

    /** @return whether the command exited successfully */
    public boolean succeeded() { return exitCode == 0; }
}
