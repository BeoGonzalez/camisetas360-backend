package com.camisetas360.analytics.service;

import com.camisetas360.analytics.model.ProductoInfo;
import com.camisetas360.analytics.model.Recomendacion;
import com.camisetas360.analytics.repository.EventoRepository;
import com.camisetas360.analytics.repository.ProductoRepository;
import com.camisetas360.analytics.repository.RecomendacionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.camisetas360.analytics.support.Eventos.USUARIO;
import static com.camisetas360.analytics.support.Eventos.envio;
import static com.camisetas360.analytics.support.Eventos.visto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-10T12:00:00Z");

    @Mock EventoRepository eventos;
    @Mock ProductoRepository productos;
    @Mock RecomendacionRepository recomendaciones;
    @Mock MetricasService metricas;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(eventos, productos, recomendaciones, new RecomendacionEngine(), metricas, 6,
                Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void registrar_guardaActualizaProductoMetricasYRecomendaciones() {
        var evento = visto("CHI-LOC", "Camiseta Chile Local", "Selecciones", 29990.0, AHORA);
        when(eventos.guardarSiNoExiste(evento)).thenReturn(true);
        when(eventos.recientes(USUARIO, AnalyticsService.HISTORIAL_ANALIZADO)).thenReturn(List.of(evento));
        when(productos.catalogo()).thenReturn(List.of(
                new ProductoInfo("CHI-LOC", "Camiseta Chile Local", "Selecciones", 29990.0, 1),
                new ProductoInfo("CHI-VIS", "Camiseta Chile Visita", "Selecciones", 27990.0, 0)));

        service.registrar(evento);

        verify(productos).registrar(evento);
        verify(metricas).registrar(evento);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Recomendacion>> items = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Double>> perfil = ArgumentCaptor.forClass(Map.class);
        verify(recomendaciones).guardar(eq(USUARIO), items.capture(), perfil.capture(), eq(AHORA));
        assertThat(items.getValue()).extracting(Recomendacion::sku).containsExactly("CHI-VIS");
        assertThat(perfil.getValue()).containsEntry("Selecciones", 1.0);
    }

    @Test
    void registrar_ignoraDuplicados() {
        var evento = visto("CHI-LOC", "Camiseta Chile Local", "Selecciones", 29990.0, AHORA);
        when(eventos.guardarSiNoExiste(evento)).thenReturn(false);

        service.registrar(evento);

        verifyNoInteractions(productos, recomendaciones, metricas);
    }

    @Test
    void registrar_envioSoloCuentaEnMetricas() {
        var evento = envio(USUARIO, AHORA);
        when(eventos.guardarSiNoExiste(evento)).thenReturn(true);

        service.registrar(evento);

        verify(metricas).registrar(evento);
        verifyNoInteractions(productos, recomendaciones);
        verify(eventos, never()).recientes(anyString(), anyInt());
    }

    @Test
    void registrar_propagaFallosDeDynamoParaQueKafkaReintente() {
        var evento = visto("CHI-LOC", "Camiseta Chile Local", "Selecciones", 29990.0, AHORA);
        when(eventos.guardarSiNoExiste(any())).thenThrow(new IllegalStateException("DynamoDB no disponible"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.registrar(evento))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(metricas);
    }
}
