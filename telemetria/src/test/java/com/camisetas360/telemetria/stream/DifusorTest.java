package com.camisetas360.telemetria.stream;

import com.camisetas360.telemetria.conexiones.ConexionesRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DifusorTest {

    static final Instant AHORA = Instant.parse("2026-11-12T10:00:00Z");
    static final ProductoTelemetria CHILE =
            new ProductoTelemetria("CHI-LOC", "Chile Local", "Selecciones", 27990.0, 3, 1, "2026-11-12T09:59:59Z");

    final ApiGatewayManagementApiClient api = mock(ApiGatewayManagementApiClient.class);
    final ConexionesRepository conexiones = mock(ConexionesRepository.class);
    final ObjectMapper json = new ObjectMapper();
    final Difusor difusor = new Difusor(api, conexiones, json, Clock.fixed(AHORA, ZoneOffset.UTC));
    final List<String> logs = new ArrayList<>();
    final Consumer<String> log = logs::add;
    final List<PostToConnectionRequest> enviados = new ArrayList<>();

    @SuppressWarnings("unchecked")
    void capturarEnvios() {
        doAnswer(inv -> {
            var builder = PostToConnectionRequest.builder();
            ((Consumer<PostToConnectionRequest.Builder>) inv.getArgument(0)).accept(builder);
            var request = builder.build();
            if ("cerrada".equals(request.connectionId())) {
                throw GoneException.builder().message("gone").build();
            }
            if ("rota".equals(request.connectionId())) {
                throw new IllegalStateException("timeout");
            }
            enviados.add(request);
            return null;
        }).when(api).postToConnection(any(Consumer.class));
    }

    @Test
    void enviaElLoteATodasLasConexionesConElFormatoAcordado() throws Exception {
        capturarEnvios();
        when(conexiones.activas(AHORA)).thenReturn(List.of("c1", "c2"));

        var resultado = difusor.difundir(List.of(CHILE), log);

        assertThat(resultado).isEqualTo(new Difusor.Resultado(2, 0, 0));
        assertThat(enviados).extracting(PostToConnectionRequest::connectionId).containsExactly("c1", "c2");
        JsonNode cuerpo = json.readTree(enviados.get(0).data().asUtf8String());
        assertThat(cuerpo.path("tipo").asText()).isEqualTo("productos.actualizados");
        assertThat(cuerpo.path("generadoEn").asText()).isEqualTo("2026-11-12T10:00:00Z");
        JsonNode producto = cuerpo.path("productos").get(0);
        assertThat(producto.path("sku").asText()).isEqualTo("CHI-LOC");
        assertThat(producto.path("vistas").asLong()).isEqualTo(3);
        assertThat(producto.path("agregados").asLong()).isEqualTo(1);
        assertThat(producto.path("precio").asDouble()).isEqualTo(27990.0);
    }

    @Test
    void conexionCerrada_seBorraYLasDemasSiguen() {
        capturarEnvios();
        when(conexiones.activas(AHORA)).thenReturn(List.of("cerrada", "rota", "ok"));

        var resultado = difusor.difundir(List.of(CHILE), log);

        assertThat(resultado).isEqualTo(new Difusor.Resultado(1, 1, 1));
        verify(conexiones).eliminar("cerrada");
        verify(conexiones, never()).eliminar("rota");
        assertThat(enviados).extracting(PostToConnectionRequest::connectionId).containsExactly("ok");
        assertThat(logs).anyMatch(l -> l.contains("rota"));
    }

    @Test
    void lotesGrandes_seDividenEnVariosMensajes() throws Exception {
        capturarEnvios();
        when(conexiones.activas(AHORA)).thenReturn(List.of("c1"));
        var productos = IntStream.range(0, Difusor.PRODUCTOS_POR_MENSAJE + 1)
                .mapToObj(i -> new ProductoTelemetria("SKU-" + i, null, null, null, i, 0, null))
                .toList();

        difusor.difundir(productos, log);

        assertThat(enviados).hasSize(2);
        assertThat(json.readTree(enviados.get(1).data().asUtf8String()).path("productos")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sinProductosOSinConexiones_noEnviaNada() {
        assertThat(difusor.difundir(List.of(), log)).isEqualTo(new Difusor.Resultado(0, 0, 0));
        verifyNoInteractions(conexiones);

        when(conexiones.activas(AHORA)).thenReturn(List.of());
        assertThat(difusor.difundir(List.of(CHILE), log)).isEqualTo(new Difusor.Resultado(0, 0, 0));
        verify(api, times(0)).postToConnection(any(Consumer.class));
    }
}
