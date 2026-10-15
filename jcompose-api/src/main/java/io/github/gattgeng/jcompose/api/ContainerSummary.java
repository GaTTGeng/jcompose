package io.github.gattgeng.jcompose.api;

import java.util.List;
import java.util.Map;

/** Summary of one project container as returned by listing operations. */
public record ContainerSummary(
        String id, String name, List<String> names, String image, String command,
        String project, String service, long created, String state, String status,
        String health, int exitCode, List<PortPublisher> publishers,
        Map<String, String> labels, List<String> networks, List<MountInfo> mounts,
        int localVolumes, long sizeRw, long sizeRootFs) {
    public ContainerSummary {
        names = names == null ? List.of() : List.copyOf(names);
        publishers = publishers == null ? List.of() : List.copyOf(publishers);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        networks = networks == null ? List.of() : List.copyOf(networks);
        mounts = mounts == null ? List.of() : List.copyOf(mounts);
    }
}
