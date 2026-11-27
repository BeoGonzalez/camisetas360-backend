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

/** Verifica las camisetas de la Bundesliga 2026/27 que siembra la migracion V9. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BundesligaSeedTest extends PostgresTestSupport {

    private static final List<String> EQUIPOS = List.of(
            "Bayern Munich", "Borussia Dortmund", "RB Leipzig", "Bayer Leverkusen", "VfB Stuttgart",
            "Eintracht Frankfurt", "SC Freiburg", "TSG Hoffenheim", "Werder Bremen", "Borussia Mönchengladbach",
            "Mainz 05", "FC Augsburg", "1. FC Köln", "Union Berlin", "Hamburger SV", "FC Schalke 04",
            "SV 07 Elversberg", "SC Paderborn 07");

    @Autowired
    private ProductRepository repository;

    // JPA-CAT-006
    @Test
    void migration_shouldSeedHomeAwayAndThirdKit_forEveryBundesligaTeam() {
        var liga = repository.findAll().stream()
                .filter(p -> "Bundesliga".equals(p.getLeague()))
                .toList();

        assertThat(liga).hasSize(54);
        assertThat(liga).extracting(Product::getSku).doesNotHaveDuplicates()
                .allMatch(sku -> sku.matches("BL-[A-Z]{3}-(LOC|VIS|ALT)-2627"));
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
        assertThat(porTipo).isEqualTo(Map.of("LOC", 18L, "VIS", 18L, "ALT", 18L));
    }
}
