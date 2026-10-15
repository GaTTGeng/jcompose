package io.github.gattgeng.jcompose.spring.boot;

import io.github.gattgeng.jcompose.api.ComposeProjectContext;
import io.github.gattgeng.jcompose.api.IComposeService;
import io.github.gattgeng.jcompose.engine.ComposeService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class JComposeAutoConfigurationTest {
    @Test
    void registersServiceAndBindsDefaultsUsedByContextFactory() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "jcompose.default-project-name", "inventory-dev",
                    "jcompose.default-socket-path", "unix:///var/run/docker.sock")));
            context.register(JComposeAutoConfiguration.class);
            context.refresh();

            assertInstanceOf(ComposeService.class, context.getBean(IComposeService.class));
            ComposeProjectContext project = context.getBean(JComposeContextFactory.class).create(Path.of("inventory"));
            assertEquals("inventory-dev", project.projectName());
            assertEquals("unix:///var/run/docker.sock", project.socketPath());
            assertEquals("compose.yaml", project.composeFileName());
        }
    }

    @Test
    void backsOffWhenApplicationProvidesComposeService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            IComposeService customService = new ComposeService();
            context.registerBean(IComposeService.class, () -> customService);
            context.register(JComposeAutoConfiguration.class);
            context.refresh();

            assertSame(customService, context.getBean(IComposeService.class));
        }
    }
}