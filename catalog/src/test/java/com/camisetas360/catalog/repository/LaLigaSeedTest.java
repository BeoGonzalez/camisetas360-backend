package com.camisetas360.catalog.repository;

import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifica las camisetas de La Liga 2026/27 que siembra la migracion V7. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LaLigaSeedTest extends PostgresTestSupport {

    private static final List<String> EQUIPOS = List.of(
            "Athletic Club", "Atlético de Madrid", "CA Osasuna", "Celta de Vigo", "Deportivo Alavés",
            "Elche CF", "FC Barcelona", "Getafe CF", "Levante UD", "Málaga CF",
            "Racing de Santander", "Rayo Vallecano", "RC Deportivo", "RCD Espanyol", "Real Betis",
            "Real Madrid", "Real Sociedad", "Sevilla FC", "Valencia CF", "Villarreal CF");

    @Autowired
    private ProductRepository repository;

    // JPA-CAT-004
    @Test
    void migration_shouldSeedHomeAwayAndThirdKit_forEveryLaLigaTeam() {
        var liga = repository.findAll().stream()
                .filter(p -> "La Liga".equals(p.getLeague()))
                .toList();

        assertThat(liga).hasSize(60);
        assertThat(liga).extracting(Product::getSku).doesNotHaveDuplicates()
                .allMatch(sku -> sku.matches("LL-[A-Z]{3}-(LOC|VIS|ALT)-2627"));
        assertThat(liga).extracting(Product::getTeam).containsOnlyElementsOf(EQUIPOS);
        assertThat(liga).allSatisfy(p -> {
            assertThat(p.getStock()).isPositive();
            assertThat(p.getCategory()).isEqualTo("Fútbol");
            // Precios en pesos chilenos enteros (V5) y equipo/tipo informados como en el panel (V4).
            assertThat(p.getPrice() % 1).isZero();
            assertThat(p.getPrice()).isBetween(79990.0, 94990.0);
            assertThat(p.getName()).isEqualTo("Camiseta " + p.getTeam() + " " + switch (p.getKitType()) {
                case LOCAL -> "Local";
                case VISITANTE -> "Visitante";
                case ALTERNATIVA -> "Alternativa";
            } + " 2026/27");
        });

        for (String equipo : EQUIPOS) {
            assertThat(liga).extracting(Product::getName).contains(
                    "Camiseta " + equipo + " Local 2026/27",
                    "Camiseta " + equipo + " Visitante 2026/27",
                    "Camiseta " + equipo + " Alternativa 2026/27");
        }
        Map<String, Long> porTipo = liga.stream().collect(
                Collectors.groupingBy(p -> p.getSku().substring(7, 10), Collectors.counting()));
        assertThat(porTipo).isEqualTo(Map.of("LOC", 20L, "VIS", 20L, "ALT", 20L));
    }
}
