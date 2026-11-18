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

/** Verifica las camisetas de la Serie A 2026/27 que siembra la migracion V6. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SerieASeedTest extends PostgresTestSupport {

    private static final List<String> EQUIPOS = List.of(
            "Inter de Milán", "AC Milan", "Juventus", "AS Roma", "SSC Nápoles",
            "SS Lazio", "Atalanta", "Fiorentina", "Bologna", "Como 1907",
            "Parma", "Torino", "Udinese", "Genoa", "Cagliari",
            "Venezia", "US Lecce", "Frosinone", "AC Monza", "US Sassuolo");

    @Autowired
    private ProductRepository repository;

    // JPA-CAT-003
    @Test
    void migration_shouldSeedHomeAwayAndThirdKit_forEverySerieATeam() {
        var premier = repository.findAll().stream()
                .filter(p -> "Serie A".equals(p.getLeague()))
                .toList();

        assertThat(premier).hasSize(60);
        assertThat(premier).extracting(Product::getSku).doesNotHaveDuplicates()
                .allMatch(sku -> sku.matches("SA-[A-Z]{3}-(LOC|VIS|ALT)-2627"));
        assertThat(premier).allSatisfy(p -> {
            assertThat(p.getPrice()).isPositive();
            assertThat(p.getStock()).isPositive();
            assertThat(p.getCategory()).isEqualTo("Fútbol");
            // Precios en pesos chilenos enteros (V5) y equipo/tipo informados como en el panel (V4).
            assertThat(p.getPrice() % 1).isZero();
            assertThat(p.getPrice()).isBetween(79990.0, 94990.0);
            assertThat(p.getTeam()).isNotBlank();
            assertThat(p.getName()).isEqualTo("Camiseta " + p.getTeam() + " " + switch (p.getKitType()) {
                case LOCAL -> "Local";
                case VISITANTE -> "Visitante";
                case ALTERNATIVA -> "Alternativa";
            } + " 2026/27");
        });

        for (String equipo : EQUIPOS) {
            assertThat(premier).extracting(Product::getName).contains(
                    "Camiseta " + equipo + " Local 2026/27",
                    "Camiseta " + equipo + " Visitante 2026/27",
                    "Camiseta " + equipo + " Alternativa 2026/27");
        }
        Map<String, Long> porTipo = premier.stream().collect(
                Collectors.groupingBy(p -> p.getSku().substring(7, 10), Collectors.counting()));
        assertThat(porTipo).isEqualTo(Map.of("LOC", 20L, "VIS", 20L, "ALT", 20L));
    }
}
