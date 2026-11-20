package com.camisetas360.kafkaui.gate;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registra {@link AdminOnlyWebFilter} en UI for Apache Kafka. La imagen lo carga con
 * {@code -Dloader.path}; {@code CAMISETAS360_ADMIN_GATE_ENABLED=false} lo apaga.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "camisetas360.admin-gate.enabled", havingValue = "true", matchIfMissing = true)
public class AdminGateAutoConfiguration {

    @Bean
    AdminOnlyWebFilter camisetas360AdminOnlyWebFilter(
            @Value("${camisetas360.admin-gate.role:ADMIN}") String role,
            @Value("${camisetas360.admin-gate.cognito-claim:cognito:groups}") String cognitoClaim,
            @Value("${camisetas360.admin-gate.azure-claim:roles}") String azureClaim,
            @Value("${server.servlet.context-path:}") String contextPath) {
        return new AdminOnlyWebFilter(role, Map.of("cognito", cognitoClaim, "azure", azureClaim), contextPath);
    }
}
