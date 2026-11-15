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

/** Verifica las camisetas de la Premier League 2026/27 que siembra la migracion V3. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PremierLeagueSeedTest extends PostgresTestSupport {

    private static final List<String> EQUIPOS = List.of(
            "Arsenal", "Aston Villa", "Bournemouth", "Brentford", "Brighton & Hove Albion",
            "Chelsea", "Coventry City", "Crystal Palace", "Everton", "Fulham",
            "Hull City", "Ipswich Town", "Leeds United", "Liverpool", "Manchester City",
            "Manchester United", "Newcastle United", "Nottingham Forest", "Sunderland", "Tottenham Hotspur");

    @Autowired
    private ProductRepository repository;

    // JPA-CAT-003
    @Test
    void migration_shouldSeedHomeAwayAndThirdKit_forEveryPremierLeagueTeam() {
        var premier = repository.findAll().stream()
                .filter(p -> "Premier League".equals(p.getLeague()))
                .toList();

        assertThat(premier).hasSize(60);
        assertThat(premier).extracting(Product::getSku).doesNotHaveDuplicates()
                .allMatch(sku -> sku.matches("PL-[A-Z]{3}-(LOC|VIS|ALT)-2627"));
        assertThat(premier).allSatisfy(p -> {
            assertThat(p.getPrice()).isPositive();
            assertThat(p.getStock()).isPositive();
            assertThat(p.getCategory()).isEqualTo("Fútbol");
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

    // JPA-CAT-003
    @Test
    void migration_shouldKeepGenericKitsWithoutLeague() {
        assertThat(repository.findBySkuInOrderByIdAsc(List.of("CAM-001", "CAM-002", "CAM-003")))
                .hasSize(3)
                .allSatisfy(p -> assertThat(p.getLeague()).isNull());
    }
}
