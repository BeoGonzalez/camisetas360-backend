package com.camisetas360.catalog.config;

import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.repository.ProductRepository;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Configuración para poblar la base de datos H2 en memoria al arrancar la aplicación.
 * Utiliza Datafaker para generar 3 equipaciones aleatorias por cada equipo de las 5 grandes ligas.
 */
@Configuration
public class DataSeederConfig {

    @Bean
    CommandLineRunner initDatabase(ProductRepository repository) {
        return args -> {
            Faker faker = new Faker(new Locale("es"));

            // Solo poblamos si la base de datos está vacía
            if (repository.count() == 0) {
                Map<String, List<String>> leaguesAndTeams = getLeaguesAndTeams();
                String[] kitTypes = {"Local", "Visita", "Tercera Equipación"};

                for (Map.Entry<String, List<String>> entry : leaguesAndTeams.entrySet()) {
                    String league = entry.getKey();
                    List<String> teams = entry.getValue();

                    for (String team : teams) {
                        for (String kitType : kitTypes) {
                            Product product = new Product();

                            String season = faker.options().option("2023/2024", "2024/2025", "Retro 1998", "Retro 2006");

                            product.setName("Camiseta " + team + " - " + kitType + " " + season);
                            product.setCategory(league);
                            product.setSku("FUT-" + faker.code().ean8());

                            double price = faker.number().randomDouble(2, 45, 120);
                            product.setPrice(price);

                            product.setStock(faker.number().numberBetween(10, 100));
                            product.setDescription("Camiseta " + kitType.toLowerCase() + " oficial del " + team + " para la competición " + league + ". Material transpirable de alta calidad.");

                            repository.save(product);
                        }
                    }
                }
                System.out.println("Base de datos H2 poblada con " + repository.count() + " camisetas en total.");
            }
        };
    }

    private Map<String, List<String>> getLeaguesAndTeams() {
        return Map.of(
                "Premier League", List.of(
                        "Arsenal FC", "Manchester City", "Chelsea FC", "Liverpool FC",
                        "Manchester United", "Tottenham Hotspur", "Newcastle United",
                        "Brighton & Hove Albion", "Brentford FC", "AFC Bournemouth",
                        "Nottingham Forest", "Crystal Palace", "Aston Villa",
                        "Everton FC", "Leeds United", "Sunderland AFC", "Fulham FC",
                        "Ipswich Town", "Coventry City", "Hull City"
                ),
                "La Liga", List.of(
                        "Real Madrid CF", "FC Barcelona", "Atlético de Madrid", "Villarreal CF",
                        "Real Sociedad", "Real Betis Balompié", "Athletic Club", "RC Celta de Vigo",
                        "Valencia CF", "RCD Espanyol", "Sevilla FC", "Levante UD",
                        "Real Racing Club", "Getafe CF", "RC Deportivo A Coruña", "Elche CF",
                        "Rayo Vallecano", "CA Osasuna", "Deportivo Alavés", "Málaga CF"
                ),
                "Serie A", List.of(
                        "Inter de Milán", "Juventus de Turín", "AS Roma", "Como 1907",
                        "AC Milan", "Atalanta de Bérgamo", "SSC Nápoles", "Fiorentina",
                        "SS Lazio", "Bolonia", "US Sassuolo", "Génova", "Udinese",
                        "Parma", "Torino FC", "Cagliari", "Venezia FC", "Frosinone Calcio",
                        "AC Monza", "US Lecce"
                ),
                "Bundesliga", List.of(
                        "Bayern Múnich", "Borussia Dortmund", "RB Leipzig", "Bayer 04 Leverkusen",
                        "VfB Stuttgart", "Eintracht Fráncfort", "TSG 1899 Hoffenheim", "SC Friburgo",
                        "1.FSV Mainz 05", "FC Augsburgo", "FC Colonia", "Borussia Mönchengladbach",
                        "1.FC Unión Berlín", "SV Werder Bremen", "Hamburgo SV", "FC Schalke 04",
                        "SV 07 Elversberg", "SC Paderborn 07"
                ),
                "Ligue 1", List.of(
                        "París Saint-Germain", "AS Mónaco", "Racing Club de Estrasburgo",
                        "Olympique de Lyon", "LOSC Lille", "Stade Rennais FC",
                        "Olympique de Marsella", "RC Lens", "Paris FC", "OGC Niza",
                        "Toulouse FC", "FC Lorient", "AJ Auxerre", "Stade Brestois 29",
                        "Angers SCO", "Le Havre AC", "ESTAC Troyes", "Le Mans FC"
                )
        );
    }
}