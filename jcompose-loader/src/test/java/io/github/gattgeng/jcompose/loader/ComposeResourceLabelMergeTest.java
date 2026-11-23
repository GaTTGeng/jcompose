package io.github.gattgeng.jcompose.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposeResourceLabelMergeTest {
    @TempDir Path directory;

    @Test
    void exposesListFormLabelsThroughTypedResources() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services: {app: {image: busybox}}
                networks: {front: {labels: [PATH, 'feature=on']}}
                volumes: {data: {labels: [PATH, 'feature=on']}}
                configs: {settings: {file: settings.txt, labels: [PATH, 'feature=on']}}
                secrets: {token: {file: token.txt, labels: [PATH, 'feature=on']}}
                """);

        var project = new ComposeFileLoader().load(directory, "compose.yaml");
        var expected = Map.of("PATH", "", "feature", "on");
        assertEquals(expected, project.networkConfigurations().getFirst().labels());
        assertEquals(expected, project.volumeConfigurations().getFirst().labels());
        assertEquals(expected, project.configConfigurations().getFirst().labels());
        assertEquals(expected, project.secretConfigurations().getFirst().labels());
    }

    @Test
    void mergesResourceLabelsAcrossListAndMappingForms() throws Exception {
        Files.writeString(directory.resolve("compose.yaml"), """
                services:
                  app: {image: busybox}
                networks:
                  front.prod:
                    labels: [base=network, shared=old]
                volumes:
                  data:
                    labels: {base: volume, shared: old}
                configs:
                  settings:
                    file: ./settings.txt
                    labels: [base=config, shared=old]
                secrets:
                  token:
                    file: ./token.txt
                    labels: {base: secret, shared: old}
                """);
        Files.writeString(directory.resolve("overlay.yaml"), """
                networks:
                  front.prod:
                    labels: {shared: new, extra: overlay}
                volumes:
                  data:
                    labels: [shared=new, extra=overlay]
                configs:
                  settings:
                    labels: {shared: new, extra: overlay}
                secrets:
                  token:
                    labels: [shared=new, extra=overlay]
                """);

        var project = new ComposeFileLoader().loadMerged(directory, List.of("compose.yaml", "overlay.yaml"));

        assertEquals(Map.of("base", "network", "shared", "new", "extra", "overlay"),
                ((Map<?, ?>) project.networkDefinitions().get("front.prod")).get("labels"));
        assertEquals(Map.of("base", "volume", "shared", "new", "extra", "overlay"),
                ((Map<?, ?>) project.volumeDefinitions().get("data")).get("labels"));
        assertEquals(Map.of("base", "config", "shared", "new", "extra", "overlay"),
                ((Map<?, ?>) project.configDefinitions().get("settings")).get("labels"));
        assertEquals(Map.of("base", "secret", "shared", "new", "extra", "overlay"),
                ((Map<?, ?>) project.secretDefinitions().get("token")).get("labels"));
    }
}
