package com.camisetas360.analytics.service;

import com.camisetas360.analytics.model.ProductoInfo;
import com.camisetas360.analytics.model.Recomendacion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.camisetas360.analytics.support.Eventos.agregado;
import static com.camisetas360.analytics.support.Eventos.eliminado;
import static com.camisetas360.analytics.support.Eventos.visto;
import static org.assertj.core.api.Assertions.assertThat;

class RecomendacionEngineTest {

    private static final Instant AHORA = Instant.parse("2026-11-10T12:00:00Z");
    private final RecomendacionEngine engine = new RecomendacionEngine();

    private static final List<ProductoInfo> CATALOGO = List.of(
            new ProductoInfo("CHI-LOC", "Camiseta Chile Local 2026", "Selecciones", 29990.0, 10),
            new ProductoInfo("CHI-VIS", "Camiseta Chile Visita 2026", "Selecciones", 27990.0, 4),
            new ProductoInfo("ARG-LOC", "Camiseta Argentina Local", "Selecciones", 31990.0, 8),
            new ProductoInfo("RM-LOC", "Camiseta Real Madrid Local", "La Liga", 34990.0, 20),
            new ProductoInfo("RM-RET", "Camiseta Real Madrid Retro 1998", "Retro", 19990.0, 2),
            new ProductoInfo("BAR-LOC", "Camiseta Barcelona Local", "La Liga", 33990.0, 15));

    @Test
    void recomienda_productosParecidosALosConsultados_sinRepetirLosYaVistos() {
        var recientes = List.of(visto("CHI-LOC", "Camiseta Chile Local 2026", "Selecciones", 29990.0, AHORA.minusSeconds(60)));

        List<Recomendacion> recomendaciones = engine.recomendar(recientes, CATALOGO, AHORA, 6);

        assertThat(recomendaciones).extracting(Recomendacion::sku).doesNotContain("CHI-LOC");
        // Misma seleccion (nombre parecido) y misma categoria: primero la camiseta de visita de Chile.
        assertThat(recomendaciones.getFirst().sku()).isEqualTo("CHI-VIS");
        assertThat(recomendaciones).extracting(Recomendacion::sku).contains("ARG-LOC")
                .doesNotContain("RM-LOC", "BAR-LOC");
        assertThat(recomendaciones).allSatisfy(r -> assertThat(r.score()).isBetween(0.0, 1.0));
        assertThat(recomendaciones).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Test
    void recomienda_porNombre_cuandoLaCategoriaEsDistinta() {
        var recientes = List.of(visto("RM-LOC", "Camiseta Real Madrid Local", "La Liga", 34990.0, AHORA));

        var recomendaciones = engine.recomendar(recientes, CATALOGO, AHORA, 6);

        assertThat(recomendaciones).extracting(Recomendacion::sku).contains("RM-RET", "BAR-LOC");
        var retro = recomendaciones.stream().filter(r -> r.sku().equals("RM-RET")).findFirst().orElseThrow();
        assertThat(retro.motivo()).isEqualTo("Parecida a Camiseta Real Madrid Local");
    }

    @Test
    void agregarAlCarrito_pesaMasQueVer() {
        var recientes = List.of(
                visto("RM-LOC", "Camiseta Real Madrid Local", "La Liga", 34990.0, AHORA),
                visto("CHI-LOC", "Camiseta Chile Local 2026", "Selecciones", 29990.0, AHORA),
                agregado("CHI-LOC", "Camiseta Chile Local 2026", "Selecciones", 29990.0, 1, AHORA));

        var recomendaciones = engine.recomendar(recientes, CATALOGO, AHORA, 2);

        assertThat(recomendaciones).extracting(Recomendacion::categoria).containsOnly("Selecciones");
        assertThat(recomendaciones.getFirst().motivo()).isEqualTo("Porque te interesan camisetas de Selecciones");
    }

    @Test
    void eliminarDelCarrito_reduceElInteres() {
        var recientes = List.of(
                agregado("RM-LOC", "Camiseta Real Madrid Local", "La Liga", 34990.0, 1, AHORA),
                eliminado("RM-LOC", "Camiseta Real Madrid Local", "La Liga", 34990.0, 1, AHORA),
                eliminado("RM-LOC", "Camiseta Real Madrid Local", "La Liga", 34990.0, 1, AHORA),
                visto("CHI-LOC", "Camiseta Chile Local 2026", "Selecciones", 29990.0, AHORA));

        var recomendaciones = engine.recomendar(recientes, CATALOGO, AHORA, 6);

        assertThat(recomendaciones).extracting(Recomendacion::categoria).doesNotContain("La Liga");
    }

    @Test
    void elInteresAntiguoDecae() {
        var recientes = List.of(
                visto("RM-LOC", "Camiseta Real Madrid Local", "La Liga", 34990.0, AHORA.minus(Duration.ofDays(7))),
                visto("CHI-LOC", "Camiseta Chile Local 2026", "Selecciones", 29990.0, AHORA));

        var recomendaciones = engine.recomendar(recientes, CATALOGO, AHORA, 1);

        assertThat(recomendaciones.getFirst().categoria()).isEqualTo("Selecciones");
    }

    @Test
    void sinHistorial_devuelveLosMasPopulares() {
        var recomendaciones = engine.recomendar(List.of(), CATALOGO, AHORA, 3);

        assertThat(recomendaciones).extracting(Recomendacion::sku).containsExactly("RM-LOC", "BAR-LOC", "CHI-LOC");
        assertThat(recomendaciones).extracting(Recomendacion::motivo).containsOnly("Popular en la tienda");
    }

    @Test
    void sinCatalogo_noRecomiendaNada() {
        assertThat(engine.recomendar(List.of(), List.of(), AHORA, 6)).isEmpty();
        assertThat(engine.recomendar(List.of(visto("X", "X", "Y", 1.0, AHORA)), List.of(), AHORA, 6)).isEmpty();
    }

    @Test
    void respetaElLimite() {
        var recientes = List.of(visto("CHI-LOC", "Camiseta Chile Local 2026", "Selecciones", 29990.0, AHORA));

        assertThat(engine.recomendar(recientes, CATALOGO, AHORA, 1)).hasSize(1);
    }

    @Test
    void perfil_ordenaCategoriasPorAfinidadNormalizada() {
        var recientes = List.of(
                agregado("CHI-LOC", "Camiseta Chile Local 2026", "Selecciones", 29990.0, 1, AHORA),
                visto("RM-LOC", "Camiseta Real Madrid Local", "La Liga", 34990.0, AHORA));

        var perfil = engine.perfil(recientes, AHORA);

        assertThat(perfil.keySet()).containsExactly("Selecciones", "La Liga");
        assertThat(perfil.get("Selecciones")).isEqualTo(1.0);
        assertThat(perfil.get("La Liga")).isEqualTo(0.333);
    }

    @Test
    void tokens_ignoraAcentosMayusculasYPalabrasGenericas() {
        assertThat(RecomendacionEngine.tokens("Camiseta Atlético de Madrid 2026"))
                .containsExactlyInAnyOrder("atletico", "madrid", "2026");
    }
}
