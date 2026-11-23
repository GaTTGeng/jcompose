package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.core.DockerClientConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComposeImageSummaryTest {
    @Test
    void usesContainerReferenceInsteadOfUnrelatedImageTag() throws Exception {
        Image image = DockerClientConfig.getDefaultObjectMapper().readValue("""
                {"Id":"sha256:abc", "RepoTags":["unrelated:latest", "registry:5000/app:used"], "Size":123}
                """, Image.class);

        var summary = ComposeService.imageSummary(image, "registry:5000/app:used");

        assertEquals("registry:5000/app", summary.repository());
        assertEquals("used", summary.tag());
        assertEquals(123, summary.size());
        assertEquals("latest", ComposeService.imageSummary(image, "registry:5000/app").tag());
        assertNull(ComposeService.imageSummary(image, "registry:5000/app@sha256:abcdef").tag());
        assertNull(ComposeService.imageSummary(image, "sha256:abcdef").repository());
    }
}
