package com.camisetas360.auth.security;

import com.camisetas360.auth.config.SecurityConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicReference;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HealthSecurityIT.HealthTestApplication.class, properties = {
        "management.health.defaults.enabled=false",
        "app.cors.allowed-origin-patterns[0]=https://frontend.example.test"
})
@AutoConfigureMockMvc
class HealthSecurityIT {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;

    @Autowired
    private AtomicReference<Health> healthState;

    // HEALTH-AUTH-001: real Actuator endpoint, infrastructure state controlled by the test.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void health_shouldBePublicWithoutDetails_whenInfrastructureIsUpOrDown(boolean up) throws Exception {
        healthState.set((up ? Health.up() : Health.down())
                .withDetail("internal", "must-not-be-exposed").build());

        mvc.perform(get("/actuator/health"))
                .andExpect(status().is(up ? 200 : 503))
                .andExpect(jsonPath("$.status").value(up ? "UP" : "DOWN"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.internal").doesNotExist())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration
    @Import(SecurityConfig.class)
    static class HealthTestApplication {
        // No component scan: no application listeners, SMTP sender or business services.
        @Bean
        AtomicReference<Health> healthState() {
            return new AtomicReference<>(Health.up().build());
        }

        @Bean
        HealthIndicator controlledHealthIndicator(AtomicReference<Health> healthState) {
            return healthState::get;
        }
    }
}
