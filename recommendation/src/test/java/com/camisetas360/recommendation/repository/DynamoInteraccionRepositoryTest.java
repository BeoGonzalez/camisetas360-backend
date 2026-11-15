package com.camisetas360.recommendation.repository;

import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.InteraccionPendiente;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoInteraccionRepositoryTest {

    private static final String TABLA = "UserInteractions";
    private static final String USUARIO = "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222";

    private final DynamoDbClient client = mock(DynamoDbClient.class);
    private final DynamoInteraccionRepository repository = new DynamoInteraccionRepository(client, TABLA);

    @Test
    void itemUsaLaClaveYLosAtributosDeLaGuia() {
        UUID eventId = UUID.fromString("c8e76a6b-2856-4b89-a477-85b88c0378bc");
        var pendiente = new InteraccionPendiente(eventId, USUARIO, "CHI-LOC", Accion.COMPRA, 3, "orders",
                Instant.parse("2026-11-10T12:00:00Z"), 0, "Camiseta Chile Local", "Selecciones", 29990.0);

        Map<String, AttributeValue> item = DynamoInteraccionRepository.item(pendiente);

        assertThat(item.get("userId").s()).isEqualTo(USUARIO);
        assertThat(item.get("timestamp").s()).isEqualTo("2026-11-10T12:00:00.000Z#" + eventId);
        assertThat(item.get("itemId").s()).isEqualTo("CHI-LOC");
        assertThat(item.get("action").s()).isEqualTo("purchase");
        assertThat(item.get("weight").n()).isEqualTo("3");
        assertThat(item.get("category").s()).isEqualTo("Selecciones");
        assertThat(item.get("name").s()).isEqualTo("Camiseta Chile Local");
        assertThat(item.get("price").n()).isEqualTo("29990.0");
        assertThat(item.get("source").s()).isEqualTo("orders");
        assertThat(item.get("eventId").s()).isEqualTo(eventId.toString());
        assertThat(item.get("occurredAt").s()).isEqualTo("2026-11-10T12:00:00.000Z");
        assertThat(item.get("occurredAtEpochMs").n()).isEqualTo("1794312000000");
    }

    @Test
    void omiteLosDatosDeProductoDesconocidos() {
        var pendiente = pendiente(UUID.randomUUID(), Accion.VISTA);

        assertThat(DynamoInteraccionRepository.item(pendiente))
                .doesNotContainKeys("category", "name", "price")
                .containsKeys("userId", "timestamp", "itemId", "action", "weight");
    }

    @Test
    void accionesConLosNombresYPesosDeLaGuia() {
        assertThat(DynamoInteraccionRepository.accionGuia(Accion.VISTA)).isEqualTo("view");
        assertThat(DynamoInteraccionRepository.accionGuia(Accion.CARRITO)).isEqualTo("cart");
        assertThat(DynamoInteraccionRepository.accionGuia(Accion.COMPRA)).isEqualTo("purchase");
    }

    @Test
    void timestampOrdenaCronologicamenteYNoColisionaEnElMismoMilisegundo() {
        Instant instante = Instant.parse("2026-11-10T12:00:00Z");
        String a = DynamoInteraccionRepository.timestamp(instante, UUID.randomUUID());
        String b = DynamoInteraccionRepository.timestamp(instante, UUID.randomUUID());
        String despues = DynamoInteraccionRepository.timestamp(instante.plusMillis(1), UUID.randomUUID());

        assertThat(a).isNotEqualTo(b);
        assertThat(a.compareTo(despues)).isNegative();
        assertThat(b.compareTo(despues)).isNegative();
    }

    @Test
    void reintentaLosNoProcesadosYDevuelveLosQueSiguenFallando() {
        var ok = pendiente(UUID.randomUUID(), Accion.VISTA);
        var rebelde = pendiente(UUID.randomUUID(), Accion.CARRITO);
        var noProcesado = WriteRequest.builder().putRequest(p -> p.item(DynamoInteraccionRepository.item(rebelde))).build();
        when(client.batchWriteItem(any(BatchWriteItemRequest.class))).thenReturn(BatchWriteItemResponse.builder()
                .unprocessedItems(Map.of(TABLA, List.of(noProcesado))).build());

        assertThat(repository.guardar(List.of(ok, rebelde))).containsExactly(rebelde.eventId());
        // Primer envio + 3 reintentos de los no procesados.
        verify(client, times(4)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void loteCompletoNoDevuelveFallidos() {
        when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
                .thenReturn(BatchWriteItemResponse.builder().unprocessedItems(Collections.emptyMap()).build());

        assertThat(repository.guardar(List.of(pendiente(UUID.randomUUID(), Accion.VISTA)))).isEmpty();
        verify(client, times(1)).batchWriteItem(any(BatchWriteItemRequest.class));
    }

    @Test
    void rechazaLotesMayoresQueBatchWriteItem() {
        var lote = IntStream.range(0, 26).mapToObj(i -> pendiente(UUID.randomUUID(), Accion.VISTA)).toList();

        assertThatThrownBy(() -> repository.guardar(lote)).isInstanceOf(IllegalArgumentException.class);
    }

    private static InteraccionPendiente pendiente(UUID eventId, Accion accion) {
        return new InteraccionPendiente(eventId, USUARIO, "ARG-VIS", accion, accion.peso(), "catalog",
                Instant.parse("2026-11-10T12:00:00Z"), 0, null, null, null);
    }
}
