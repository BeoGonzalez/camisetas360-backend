package com.camisetas360.kafkaui.gate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

class AdminGateAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AdminGateAutoConfiguration.class));

    @Test
    void registraElFiltroPorDefecto() {
        runner.withPropertyValues("server.servlet.context-path=/kafka-ui")
                .run(context -> assertThat(context).hasSingleBean(AdminOnlyWebFilter.class));
    }

    @Test
    void sePuedeApagar() {
        runner.withPropertyValues("camisetas360.admin-gate.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AdminOnlyWebFilter.class));
    }

    @Test
    void seDescubreComoAutoConfiguracion() throws Exception {
        var imports = getClass().getClassLoader().getResource(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(imports).isNotNull();
        try (var in = imports.openStream()) {
            assertThat(new String(in.readAllBytes())).contains(AdminGateAutoConfiguration.class.getName());
        }
    }
}
