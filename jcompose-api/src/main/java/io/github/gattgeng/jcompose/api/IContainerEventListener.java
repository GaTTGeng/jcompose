package io.github.gattgeng.jcompose.api;

/** Callback interface for observing lifecycle events of project containers. */
@FunctionalInterface
public interface IContainerEventListener {
    /** Called once per container lifecycle event observed by the engine. */
    void onContainerEvent(ContainerEvent containerEvent);
}
