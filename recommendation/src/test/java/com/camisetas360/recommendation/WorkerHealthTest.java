package com.camisetas360.recommendation;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration,org.springframework.ai.model.mistralai.autoconfigure.MistralAiChatAutoConfiguration,org.springframework.ai.model.mistralai.autoconfigure.MistralAiEmbeddingAutoConfiguration,org.springframework.ai.model.mistralai.autoconfigure.MistralAiModerationAutoConfiguration,org.springframework.ai.model.mistralai.autoconfigure.MistralAiOcrAutoConfiguration",
    "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
    "management.health.defaults.enabled=false",
    "management.endpoint.health.group.readiness.include=readinessState",
    "RABBITMQ_HOST=unused.invalid", "RABBITMQ_USERNAME=test",
    "RABBITMQ_PASSWORD=test", "RABBITMQ_VHOST=/test"
})
@AutoConfigureMockMvc
@Import(WorkerHealthTest.Dependencies.class)
class WorkerHealthTest {
    @MockitoBean JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AtomicReference<Health> dependency;

    @Test void healthRequiresNoBearerAndHidesDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.details").doesNotExist())
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test void dependencyFailureDoesNotTriggerLivenessFailure() throws Exception {
        dependency.set(Health.down().withDetail("secret", "redacted").build());
        try {
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        } finally { dependency.set(Health.up().build()); }
    }

    @Test void businessAndSensitiveOperationalEndpointsDoNotExist() throws Exception {
        for (String path : new String[]{"/api/v1/recommendations", "/actuator/env", "/actuator/info", "/actuator/beans"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class Dependencies {
        @Bean AtomicReference<Health> dependency() { return new AtomicReference<>(Health.up().build()); }
        @Bean HealthIndicator controlledHealthIndicator(AtomicReference<Health> dependency) { return dependency::get; }
    }
}
