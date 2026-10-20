package io.github.gattgeng.jcompose.api;

/** Controls how fields without complete Docker Engine support are handled. */
public enum ComposeValidationMode {
    /** Load the project and report unsupported or partially supported fields. */
    REPORT,
    /** Reject the project before an operation starts when such a field is present. */
    STRICT
}
