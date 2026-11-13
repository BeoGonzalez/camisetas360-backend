package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.InteraccionPendiente;
import com.camisetas360.recommendation.repository.DynamoInteraccionRepository;
import com.camisetas360.recommendation.repository.JdbcSincronizacionDynamoRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SincronizadorDynamoDbTest {

    private final JdbcSincronizacionDynamoRepository outbox = mock(JdbcSincronizacionDynamoRepository.class);
    private final DynamoInteraccionRepository dynamo = mock(DynamoInteraccionRepository.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final SincronizadorDynamoDb sincronizador = new SincronizadorDynamoDb(
            outbox, dynamo, mock(PlatformTransactionManager.class), metrics, 40);

    @Test
    void loteEnviadoSeMarcaComoSincronizado() {
        var lote = List.of(pendiente(0), pendiente(0));
        when(outbox.bloquearPendientes(25)).thenReturn(lote);
        when(dynamo.guardar(lote)).thenReturn(Set.of());

        assertThat(sincronizador.enviarLote()).isFalse(); // lote incompleto: no quedan pendientes

        verify(outbox).marcarSincronizadas(List.of(lote.get(0).eventId(), lote.get(1).eventId()));
        verify(outbox, never()).posponer(eq(lote.get(0).eventId()), anyInt(), anyLong());
        assertThat(metrics.counter(SincronizadorDynamoDb.METRICA, "result", "sent").count()).isEqualTo(2);
    }

    @Test
    void loteLlenoPideOtroCiclo() {
        var lote = IntStream.range(0, 25).mapToObj(i -> pendiente(0)).toList();
        when(outbox.bloquearPendientes(25)).thenReturn(lote);
        when(dynamo.guardar(lote)).thenReturn(Set.of());

        assertThat(sincronizador.enviarLote()).isTrue();
    }

    @Test
    void noProcesadosSePosponenConBackoffYElRestoSeMarca() {
        var ok = pendiente(0);
        var fallida = pendiente(2);
        when(outbox.bloquearPendientes(25)).thenReturn(List.of(ok, fallida));
        when(dynamo.guardar(List.of(ok, fallida))).thenReturn(Set.of(fallida.eventId()));

        assertThat(sincronizador.enviarLote()).isFalse();

        verify(outbox).marcarSincronizadas(List.of(ok.eventId()));
        verify(outbox).posponer(fallida.eventId(), 3, 20L);
        assertThat(metrics.counter(SincronizadorDynamoDb.METRICA, "result", "retry").count()).isEqualTo(1);
    }

    @Test
    void dynamoCaidoPosponeTodoElLoteSinPerderlo() {
        var a = pendiente(0);
        var b = pendiente(6);
        when(outbox.bloquearPendientes(25)).thenReturn(List.of(a, b));
        when(dynamo.guardar(List.of(a, b))).thenThrow(SdkClientException.create("credenciales vencidas"));

        assertThat(sincronizador.enviarLote()).isFalse();

        verify(outbox).marcarSincronizadas(List.of());
        verify(outbox).posponer(a.eventId(), 1, 5L);
        verify(outbox).posponer(b.eventId(), 7, 300L);
    }

    @Test
    void sinPendientesNoLlamaADynamo() {
        when(outbox.bloquearPendientes(25)).thenReturn(List.of());

        assertThat(sincronizador.enviarLote()).isFalse();

        verify(dynamo, never()).guardar(org.mockito.ArgumentMatchers.any());
        verify(outbox, times(0)).marcarSincronizadas(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void backoffExponencialHastaCincoMinutos() {
        assertThat(IntStream.rangeClosed(1, 9).mapToLong(SincronizadorDynamoDb::esperaSegundos).boxed().toList())
                .containsExactly(5L, 10L, 20L, 40L, 80L, 160L, 300L, 300L, 300L);
    }

    private static InteraccionPendiente pendiente(int intentos) {
        return new InteraccionPendiente(UUID.randomUUID(), "t:o", "CHI-LOC", Accion.VISTA, 1, "catalog",
                Instant.parse("2026-11-10T12:00:00Z"), intentos, null, null, null);
    }
}
