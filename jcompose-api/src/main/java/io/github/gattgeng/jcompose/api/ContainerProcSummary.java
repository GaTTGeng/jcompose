package io.github.gattgeng.jcompose.api;

import java.util.List;

/** Process listing of one service container. */
public record ContainerProcSummary(String id, String name, String service, String replica,
                                   List<String> titles, List<List<String>> processes) {
    public ContainerProcSummary {
        titles = titles == null ? List.of() : List.copyOf(titles);
        processes = processes == null ? List.of() : processes.stream().map(List::copyOf).toList();
    }
}
