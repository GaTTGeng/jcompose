package io.github.gattgeng.jcompose.engine;

import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import com.github.dockerjava.api.model.EventType;
import io.github.gattgeng.jcompose.api.ComposeEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComposeEventMappingTest {
    @Test
    void keepsMissingActorAttributesNullAndRequiredTextFieldsNonNull() {
        ComposeEvent event = ComposeService.toComposeEvent(new Event());

        assertEquals("", event.id());
        assertEquals("", event.action());
        assertEquals("", event.type());
        assertNull(event.attributes());
        assertNull(event.service());
        assertNull(event.container());
    }

    @Test
    void mapsActorIdentityServiceAndEventMetadata() {
        Event source = new Event()
                .withType(EventType.CONTAINER)
                .withAction("start")
                .withId("fallback-id")
                .withTime(1_700_000_000L)
                .withEventActor(new EventActor()
                        .withId("container-123")
                        .withAttributes(Map.of(
                                "com.docker.compose.project", "demo",
                                "com.docker.compose.service", "web")));

        ComposeEvent event = ComposeService.toComposeEvent(source);

        assertEquals("container", event.type());
        assertEquals("start", event.action());
        assertEquals("container-123", event.id());
        assertEquals("container-123", event.container());
        assertEquals("web", event.service());
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), event.timestamp());
        assertEquals("demo", event.attributes().get("com.docker.compose.project"));
    }

    @Test
    void prefersNanosecondEventTimestamp() {
        Event source = new Event()
                .withTime(1_700_000_000L)
                .withTimenano(1_700_000_000_500_000_000L);

        ComposeEvent event = ComposeService.toComposeEvent(source);

        assertEquals(Instant.ofEpochSecond(1_700_000_000L, 500_000_000L), event.timestamp());
    }
}
