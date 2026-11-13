package com.camisetas360.recommendation.ml;

import com.camisetas360.recommendation.ml.JdbcDatosModeloRepository.Par;
import com.camisetas360.recommendation.model.ProductoFicha;
import com.camisetas360.recommendation.service.ModeloRecomendaciones;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModeloEntrenadoTest {

    private static final Instant AHORA = Instant.parse("2026-11-12T19:00:00Z");
    private static final FactorizacionTensorFlow.Parametros PARAMETROS = FactorizacionTensorFlow.Parametros.porDefecto();

    private static List<Par> comunidades() {
        List<Par> pares = new ArrayList<>();
        for (int n = 0; n < 8; n++) {
            for (String sku : List.of("CHI-LOC", "CHI-VIS", "CHI-RET", "CHI-ARQ")) pares.add(new Par("chi-" + n, sku, 2, false));
            for (String sku : List.of("ARG-LOC", "ARG-VIS", "ARG-RET", "ARG-ARQ")) pares.add(new Par("arg-" + n, sku, 2, false));
        }
        pares.add(new Par("nuevo", "CHI-LOC", 1, false));
        pares.add(new Par("nuevo", "CHI-VIS", 4, true)); // vio y compro
        return pares;
    }

    @Test
    void recomiendaLoQueEligieronUsuariosParecidosSinRepetirCompras() {
        var modelo = ModeloEntrenado.construir(comunidades(), Map.of(
                "CHI-RET", new ProductoFicha("CHI-RET", "Chile Retro", "Selecciones", 24990.0)), 20, PARAMETROS, AHORA);

        var resultado = modelo.recomendar("nuevo", 3);

        assertThat(modelo.conTensorFlow()).isTrue();
        assertThat(modelo.version()).isEqualTo("20261112T190000Z");
        assertThat(resultado.personalizado()).isTrue();
        assertThat(resultado.items()).extracting(ModeloRecomendaciones.Item::sku)
                .containsExactlyInAnyOrder("CHI-LOC", "CHI-RET", "CHI-ARQ")
                .doesNotContain("CHI-VIS");
        assertThat(resultado.items()).filteredOn(i -> i.sku().equals("CHI-RET")).singleElement()
                .satisfies(i -> assertThat(i.nombre()).isEqualTo("Chile Retro"));
        assertThat(resultado.items()).extracting(ModeloRecomendaciones.Item::score).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void usuarioSinHistorialRecibePopulares() {
        var modelo = ModeloEntrenado.construir(comunidades(), Map.of(), 20, PARAMETROS, AHORA);

        var resultado = modelo.recomendar("desconocido", 2);

        assertThat(resultado.personalizado()).isFalse();
        assertThat(resultado.items()).hasSize(2);
        assertThat(resultado.items().getFirst().score()).isEqualTo(1.0);
    }

    @Test
    void conPocosDatosNoEntrenaYUsaPopularidad() {
        var modelo = ModeloEntrenado.construir(List.of(new Par("solo", "X", 3, true)), Map.of(), 20, PARAMETROS, AHORA);

        assertThat(modelo.conTensorFlow()).isFalse();
        assertThat(modelo.recomendar("otro", 5).items()).extracting(ModeloRecomendaciones.Item::sku).containsExactly("X");
        assertThat(modelo.recomendar("solo", 5).items()).isEmpty(); // ya lo compro
    }
}
