package io.github.gattgeng.jcompose.spring.boot;

import io.github.gattgeng.jcompose.api.IComposeService;
import io.github.gattgeng.jcompose.engine.ComposeService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Spring Boot auto-configuration for the jcompose engine and context factory. */
@AutoConfiguration
@EnableConfigurationProperties(JComposeProperties.class)
public class JComposeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(IComposeService.class)
    IComposeService jcomposeService() {
        return new ComposeService();
    }

    @Bean
    @ConditionalOnMissingBean
    JComposeContextFactory jcomposeContextFactory(JComposeProperties properties) {
        return new JComposeContextFactory(properties);
    }
}
