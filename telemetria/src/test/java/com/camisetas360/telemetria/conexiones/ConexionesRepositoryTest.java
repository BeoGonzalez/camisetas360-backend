package com.camisetas360.telemetria.conexiones;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConexionesRepositoryTest {

    static final Instant AHORA = Instant.parse("2026-11-12T10:00:00Z");

    final DynamoDbClient client = mock(DynamoDbClient.class);
    final ConexionesRepository repo = new ConexionesRepository(client, "ConexionesWebSocket", Duration.ofHours(2));

    @Test
    @SuppressWarnings("unchecked")
    void guardar_escribeUsuarioYTtl() {
        var capturado = new PutItemRequest[1];
        doAnswer(inv -> {
            var b = PutItemRequest.builder();
            ((Consumer<PutItemRequest.Builder>) inv.getArgument(0)).accept(b);
            capturado[0] = b.build();
            return null;
        }).when(client).putItem(any(Consumer.class));

        repo.guardar("c1", "tid:oid", AHORA);

        assertThat(capturado[0].tableName()).isEqualTo("ConexionesWebSocket");
        assertThat(capturado[0].item()).containsEntry("connectionId", AttributeValue.fromS("c1"))
                .containsEntry("usuarioId", AttributeValue.fromS("tid:oid"))
                .containsEntry("expiresAt", AttributeValue.fromN(Long.toString(AHORA.plusSeconds(7200).getEpochSecond())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eliminar_borraPorConnectionId() {
        var capturado = new DeleteItemRequest[1];
        doAnswer(inv -> {
            var b = DeleteItemRequest.builder();
            ((Consumer<DeleteItemRequest.Builder>) inv.getArgument(0)).accept(b);
            capturado[0] = b.build();
            return null;
        }).when(client).deleteItem(any(Consumer.class));

        repo.eliminar("c1");

        assertThat(capturado[0].key()).isEqualTo(Map.of("connectionId", AttributeValue.fromS("c1")));
    }

    @Test
    void activas_recorreTodasLasPaginasYDescartaLasVencidas() {
        var pagina1 = ScanResponse.builder()
                .items(List.of(item("c1", AHORA.plusSeconds(60)), item("vencida", AHORA.minusSeconds(1))))
                .lastEvaluatedKey(Map.of("connectionId", AttributeValue.fromS("vencida")))
                .build();
        var pagina2 = ScanResponse.builder()
                .items(List.of(Map.of("connectionId", AttributeValue.fromS("sin-ttl"))))
                .build();
        when(client.scan(any(ScanRequest.class))).thenReturn(pagina1, pagina2);

        assertThat(repo.activas(AHORA)).containsExactly("c1", "sin-ttl");

        var peticiones = ArgumentCaptor.forClass(ScanRequest.class);
        verify(client, org.mockito.Mockito.times(2)).scan(peticiones.capture());
        assertThat(peticiones.getAllValues().get(1).exclusiveStartKey())
                .containsEntry("connectionId", AttributeValue.fromS("vencida"));
    }

    static Map<String, AttributeValue> item(String id, Instant expira) {
        return Map.of("connectionId", AttributeValue.fromS(id),
                "expiresAt", AttributeValue.fromN(Long.toString(expira.getEpochSecond())));
    }
}
