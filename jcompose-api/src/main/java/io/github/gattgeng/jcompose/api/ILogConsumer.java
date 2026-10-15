package io.github.gattgeng.jcompose.api;

/** Receives container log lines and status changes from streaming project operations. */
public interface ILogConsumer {
    /** Receives one line from a service container. */
    void onLog(String serviceName, String message, boolean standardError);

    /** Signals that the selected service log stream has ended. */
    void onLogComplete(String serviceName);

    /** Receives a progress or status message that is not container output. */
    void onStatus(String serviceName, String message);
}
