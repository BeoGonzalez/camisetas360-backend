package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.Recomendacion;
import com.camisetas360.recommendation.service.RecomendacionesConsulta.Fuente;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecomendacionesConsultaTest {

    private static final String USUARIO = "t:o";

    private final RecomendacionService ponderadas = mock(RecomendacionService.class);
    private final ModeloRecomendaciones modelo = mock(ModeloRecomendaciones.class);
    private final RecomendacionesConsulta consulta = new RecomendacionesConsulta(ponderadas, Optional.of(modelo));

    @Test
    void usaElModeloCuandoConoceAlUsuario() {
        when(modelo.recomendar(USUARIO, 5)).thenReturn(Optional.of(new ModeloRecomendaciones.Resultado(true, "v1",
                List.of(new ModeloRecomendaciones.Item("ARG-VIS", "Argentina Visita", "Selecciones", 34990.0, 0.97)))));

        var resultado = consulta.consultar(USUARIO);

        assertThat(resultado.fuente()).isEqualTo(Fuente.MODELO);
        assertThat(resultado.versionModelo()).isEqualTo("v1");
        assertThat(resultado.items()).singleElement().satisfies(i -> {
            assertThat(i.posicion()).isEqualTo(1);
            assertThat(i.sku()).isEqualTo("ARG-VIS");
            assertThat(i.score()).isEqualTo(0.97);
            assertThat(i.puntaje()).isNull();
        });
        verify(ponderadas, never()).top5(USUARIO);
    }

    @Test
    void siElModeloSoloTienePopularesPrefiereElTop5PonderadoDelUsuario() {
        // El usuario tiene historial en PostgreSQL pero el modelo aun no se reentreno con el.
        when(modelo.recomendar(USUARIO, 5)).thenReturn(Optional.of(new ModeloRecomendaciones.Resultado(false, "v1",
                List.of(new ModeloRecomendaciones.Item("POP-1", null, null, null, 1.0)))));
        when(ponderadas.top5(USUARIO)).thenReturn(List.of(new Recomendacion(1, "CHI-LOC", 3, "Chile", "Selecciones", 29990.0)));

        var resultado = consulta.consultar(USUARIO);

        assertThat(resultado.fuente()).isEqualTo(Fuente.PESOS);
        assertThat(resultado.items()).extracting(RecomendacionesConsulta.Item::sku).containsExactly("CHI-LOC");
        assertThat(resultado.items().getFirst().puntaje()).isEqualTo(3);
    }

    @Test
    void usuarioSinHistorialRecibeLosPopularesDelModelo() {
        when(modelo.recomendar(USUARIO, 5)).thenReturn(Optional.of(new ModeloRecomendaciones.Resultado(false, "v1",
                List.of(new ModeloRecomendaciones.Item("POP-1", null, null, null, 1.0)))));
        when(ponderadas.top5(USUARIO)).thenReturn(List.of());

        var resultado = consulta.consultar(USUARIO);

        assertThat(resultado.fuente()).isEqualTo(Fuente.POPULARES);
        assertThat(resultado.items()).extracting(RecomendacionesConsulta.Item::sku).containsExactly("POP-1");
    }

    @Test
    void modeloCaidoUsaElTop5Ponderado() {
        when(modelo.recomendar(USUARIO, 5)).thenReturn(Optional.empty());
        when(ponderadas.top5(USUARIO)).thenReturn(List.of(new Recomendacion(1, "CHI-LOC", 3, null, null, null)));

        assertThat(consulta.consultar(USUARIO).fuente()).isEqualTo(Fuente.PESOS);
    }

    @Test
    void sinModeloConfiguradoFuncionaComoLaGuiaSimple() {
        var sinModelo = new RecomendacionesConsulta(ponderadas, Optional.empty());
        when(ponderadas.top5(USUARIO)).thenReturn(List.of());

        var resultado = sinModelo.consultar(USUARIO);

        assertThat(resultado.fuente()).isEqualTo(Fuente.PESOS);
        assertThat(resultado.items()).isEmpty();
    }

    @Test
    void recortaAlLimiteYNumeraLasPosiciones() {
        var muchos = IntStream.rangeClosed(1, 8)
                .mapToObj(n -> new ModeloRecomendaciones.Item("SKU-" + n, null, null, null, 1.0 - n / 10.0)).toList();
        when(modelo.recomendar(USUARIO, 5)).thenReturn(Optional.of(new ModeloRecomendaciones.Resultado(true, "v1", muchos)));

        assertThat(consulta.consultar(USUARIO).items()).extracting(RecomendacionesConsulta.Item::posicion)
                .containsExactly(1, 2, 3, 4, 5);
    }
}
