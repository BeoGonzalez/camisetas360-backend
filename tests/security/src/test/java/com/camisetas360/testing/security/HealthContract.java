package com.camisetas360.testing.security;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public interface HealthContract {
    MockMvc mvc();
    AtomicReference<Health> dependency();
    @Test default void healthIsPublicAndRedactsDetails() throws Exception {
        mvc().perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }
    @Test default void dependencyFailureDoesNotBreakLiveness() throws Exception {
        dependency().set(Health.down().withDetail("secret", "never-public").build());
        try {
            mvc().perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.details").doesNotExist())
                    .andExpect(jsonPath("$.components").doesNotExist());
        } finally { dependency().set(Health.up().build()); }
    }
    /**
     * El health check del target group del NLB en AWS es /actuator/health/readiness: debe ser publico,
     * sin detalles y no depender de la BD ni de los brokers (si no, una caida saca todas las tareas).
     */
    @Test default void readinessIsPublicAndIgnoresDependencies() throws Exception {
        dependency().set(Health.down().withDetail("secret", "never-public").build());
        try {
            mvc().perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.details").doesNotExist())
                    .andExpect(jsonPath("$.components").doesNotExist())
                    .andExpect(header().doesNotExist("WWW-Authenticate"));
        } finally { dependency().set(Health.up().build()); }
    }
}
