package com.camisetas360.recommendation.ml;

import com.camisetas360.recommendation.ml.JdbcDatosModeloRepository.Par;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModeloTensorFlowTest {

    private final JdbcDatosModeloRepository datos = mock(JdbcDatosModeloRepository.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ModeloTensorFlow modelo = new ModeloTensorFlow(datos,
            Clock.fixed(Instant.parse("2026-11-12T19:00:00Z"), ZoneOffset.UTC), metrics, 8, 10, 2, 20, 1000);

    @Test
    void sinEntrenarNoRecomiendaParaQueRijaElTop5Ponderado() {
        assertThat(modelo.recomendar("a", 5)).isEmpty();
    }

    @Test
    void reentrenarPublicaUnModeloNuevo() {
        when(datos.pares(anyInt())).thenReturn(List.of(new Par("a", "X", 3, false), new Par("b", "Y", 1, false)));
        when(datos.productos()).thenReturn(Map.of());

        modelo.reentrenar();

        assertThat(modelo.vigente()).hasValueSatisfying(m -> assertThat(m.conTensorFlow()).isTrue());
        assertThat(modelo.recomendar("a", 5)).hasValueSatisfying(r -> assertThat(r.personalizado()).isTrue());
        assertThat(metrics.counter("recommendation.tensorflow.entrenamientos", "result", "ok").count()).isEqualTo(1);
    }

    @Test
    void siFallaElReentrenamientoConservaElModeloAnterior() {
        when(datos.pares(anyInt())).thenReturn(List.of(new Par("a", "X", 3, false), new Par("b", "Y", 1, false)));
        when(datos.productos()).thenReturn(Map.of());
        modelo.reentrenar();
        var anterior = modelo.vigente().orElseThrow();

        when(datos.pares(anyInt())).thenThrow(new DataAccessResourceFailureException("postgres caido"));
        modelo.reentrenar();

        assertThat(modelo.vigente()).containsSame(anterior);
        assertThat(metrics.counter("recommendation.tensorflow.entrenamientos", "result", "error").count()).isEqualTo(1);
    }
}
