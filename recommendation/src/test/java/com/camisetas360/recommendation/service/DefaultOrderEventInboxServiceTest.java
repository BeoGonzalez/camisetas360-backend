package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.exception.InvalidEventContractException;
import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.parser.OrderEventParser;
import com.camisetas360.recommendation.parser.ParsedOrderEvent;
import com.camisetas360.recommendation.repository.OrderInboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultOrderEventInboxServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-11T10:00:00Z");
    private static final String USUARIO = "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222";

    private final OrderEventParser parser = mock(OrderEventParser.class);
    private final OrderInboxRepository repository = mock(OrderInboxRepository.class);
    private final RecomendacionService recomendaciones = mock(RecomendacionService.class);
    private final DefaultOrderEventInboxService service = new DefaultOrderEventInboxService(
            parser, repository, recomendaciones, Clock.fixed(AHORA, ZoneOffset.UTC));
    private final byte[] body = "{\"eventId\":\"test\"}".getBytes(StandardCharsets.UTF_8);
    private final String raw = new String(body, StandardCharsets.UTF_8);

    @Test
    @DisplayName("Cada linea de una orden nueva se registra como COMPRA de peso 3")
    void registraCadaLineaComoCompra() {
        UUID eventId = UUID.randomUUID();
        var parsed = new ParsedOrderEvent(eventId, 42L, USUARIO, List.of(
                new ParsedOrderEvent.Linea("CHI-LOC", 2, 29990.0),
                new ParsedOrderEvent.Linea("ARG-VIS", 1, null)), null);
        when(parser.parse(body)).thenReturn(parsed);
        when(repository.saveIfAbsent(eventId, 42L, raw)).thenReturn(true);

        assertThat(service.accept(body)).isTrue();

        List<Interaccion> compras = comprasRegistradas();
        assertThat(compras).extracting(Interaccion::sku).containsExactly("CHI-LOC", "ARG-VIS");
        assertThat(compras).allSatisfy(i -> {
            assertThat(i.accion()).isEqualTo(Accion.COMPRA);
            assertThat(i.usuarioId()).isEqualTo(USUARIO);
            assertThat(i.origen()).isEqualTo("orders");
            assertThat(i.ocurridoEn()).isEqualTo(AHORA);
        });
        assertThat(compras.get(0).producto().precio()).isEqualTo(29990.0);
        assertThat(compras.get(0).eventId())
                .isEqualTo(DefaultOrderEventInboxService.idDeLinea(eventId, "CHI-LOC"))
                .isNotEqualTo(compras.get(1).eventId());
        verify(repository).markProcessed(eventId);
    }

    @Test
    @DisplayName("Usa occurredAt del evento cuando viene informado")
    void usaOccurredAtDelEvento() {
        UUID eventId = UUID.randomUUID();
        Instant ocurrido = Instant.parse("2026-11-01T00:00:00Z");
        when(parser.parse(body)).thenReturn(new ParsedOrderEvent(eventId, 42L, USUARIO,
                List.of(new ParsedOrderEvent.Linea("CHI-LOC", 1, null)), ocurrido));
        when(repository.saveIfAbsent(eventId, 42L, raw)).thenReturn(true);

        service.accept(body);

        assertThat(comprasRegistradas()).singleElement()
                .extracting(Interaccion::ocurridoEn).isEqualTo(ocurrido);
    }

    @Test
    @DisplayName("El id por linea es determinista: reprocesar la orden no duplica la compra")
    void idDeLineaEsDeterminista() {
        UUID eventId = UUID.randomUUID();
        assertThat(DefaultOrderEventInboxService.idDeLinea(eventId, "A"))
                .isEqualTo(DefaultOrderEventInboxService.idDeLinea(eventId, "A"))
                .isNotEqualTo(DefaultOrderEventInboxService.idDeLinea(eventId, "B"))
                .isNotEqualTo(DefaultOrderEventInboxService.idDeLinea(UUID.randomUUID(), "A"));
    }

    @Test
    @DisplayName("Un payload legacy sin propietario se guarda en el inbox pero no genera compras")
    void eventoSinPropietarioNoRegistraCompras() {
        UUID eventId = UUID.randomUUID();
        when(parser.parse(body)).thenReturn(new ParsedOrderEvent(eventId, 42L, null,
                List.of(new ParsedOrderEvent.Linea("CHI-LOC", 1, null)), null));
        when(repository.saveIfAbsent(eventId, 42L, raw)).thenReturn(true);

        assertThat(service.accept(body)).isTrue();

        verifyNoInteractions(recomendaciones);
        verify(repository).markProcessed(eventId);
    }

    @Test
    @DisplayName("Un evento duplicado no vuelve a registrar compras")
    void eventoDuplicado() {
        UUID eventId = UUID.randomUUID();
        when(parser.parse(body)).thenReturn(new ParsedOrderEvent(eventId, 42L, USUARIO,
                List.of(new ParsedOrderEvent.Linea("CHI-LOC", 1, null)), null));
        when(repository.saveIfAbsent(eventId, 42L, raw)).thenReturn(false);

        assertThat(service.accept(body)).isFalse();

        verifyNoInteractions(recomendaciones);
        verify(repository, never()).markProcessed(any());
    }

    @Test
    @DisplayName("Si falla el registro, la excepcion se propaga para reintentar y no se marca procesado")
    void falloSePropaga() {
        UUID eventId = UUID.randomUUID();
        when(parser.parse(body)).thenReturn(new ParsedOrderEvent(eventId, 42L, USUARIO,
                List.of(new ParsedOrderEvent.Linea("CHI-LOC", 1, null)), null));
        when(repository.saveIfAbsent(eventId, 42L, raw)).thenReturn(true);
        doThrow(new IllegalStateException("db caida")).when(recomendaciones).registrarTodas(any());

        assertThatThrownBy(() -> service.accept(body)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).markProcessed(any());
    }

    @SuppressWarnings("unchecked")
    private List<Interaccion> comprasRegistradas() {
        ArgumentCaptor<List<Interaccion>> captor = ArgumentCaptor.forClass(List.class);
        // Una sola llamada por orden: un lock de usuario y un recalculo del Top 5.
        verify(recomendaciones).registrarTodas(captor.capture());
        verify(recomendaciones, never()).registrar(any());
        return captor.getValue();
    }

    @Test
    @DisplayName("Un payload invalido no llega al repositorio")
    void parseoInvalido() {
        when(parser.parse(body)).thenThrow(new InvalidEventContractException("Invalid JSON"));

        assertThatThrownBy(() -> service.accept(body)).isInstanceOf(InvalidEventContractException.class);

        verifyNoInteractions(repository, recomendaciones);
    }
}
