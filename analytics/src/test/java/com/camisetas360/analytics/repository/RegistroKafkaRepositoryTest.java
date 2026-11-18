package com.camisetas360.analytics.repository;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RegistroKafkaRepositoryTest {

    private static final Instant AHORA = Instant.parse("2026-11-17T10:00:00Z");
    private static final Instant PRODUCIDO = Instant.parse("2026-11-17T09:59:58Z");

    private final DynamoDbClient client = mock(DynamoDbClient.class);
    private final RegistroKafkaRepository repository = new RegistroKafkaRepository(client,
            new DynamoDbTables("p-"), Duration.ofDays(90), Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void guardaElMensajeCompletoConClaveOrdenadaPorParticionYOffset() {
        var headers = new RecordHeaders();
        headers.add("__TypeId__", "PagoAutorizado".getBytes(StandardCharsets.UTF_8));
        headers.add("vacio", null);
        var registro = new ConsumerRecord<>("pago.autorizado", 2, 41L, PRODUCIDO.toEpochMilli(),
                TimestampType.CREATE_TIME, 0, 0, "orden-1", "{\"ordenId\":\"orden-1\"}", headers, Optional.empty());

        repository.guardar(registro);

        var item = capturar().item();
        assertThat(capturar().tableName()).isEqualTo("p-kafka-registros");
        assertThat(item.get("topic").s()).isEqualTo("pago.autorizado");
        assertThat(item.get("registro").s()).isEqualTo("00002#0000000000000000041");
        assertThat(item.get("particion").n()).isEqualTo("2");
        assertThat(item.get("offset").n()).isEqualTo("41");
        assertThat(item.get("clave").s()).isEqualTo("orden-1");
        assertThat(item.get("valor").s()).isEqualTo("{\"ordenId\":\"orden-1\"}");
        assertThat(item.get("timestamp").s()).isEqualTo(PRODUCIDO.toString());
        assertThat(item.get("timestampTipo").s()).isEqualTo("CreateTime");
        assertThat(item.get("recibidoEn").s()).isEqualTo(AHORA.toString());
        assertThat(item.get("headers").m()).containsOnlyKeys("__TypeId__");
        assertThat(item.get("expiresAt").n()).isEqualTo(String.valueOf(PRODUCIDO.plus(Duration.ofDays(90)).getEpochSecond()));
        assertThat(item).doesNotContainKey("truncado");
    }

    @Test
    void sinClaveNiValorNiTimestamp_usaLaHoraDeRecepcion() {
        repository.guardar(new ConsumerRecord<>("carrito.item-agregado", 0, 0L, null, null));

        var item = capturar().item();
        assertThat(item).doesNotContainKeys("clave", "valor", "headers");
        assertThat(item.get("timestamp").s()).isEqualTo(AHORA.toString());
    }

    @Test
    void valorMayorAlLimiteDeDynamoDb_seRecorta() {
        String enorme = "x".repeat(RegistroKafkaRepository.MAX_VALOR_BYTES + 10);

        repository.guardar(new ConsumerRecord<>("catalogo.producto-visto", 0, 7L, "k", enorme));

        var item = capturar().item();
        assertThat(item.get("valor").s()).hasSize(RegistroKafkaRepository.MAX_VALOR_BYTES);
        assertThat(item.get("truncado").bool()).isTrue();
    }

    @Test
    void constructorDeSpring_usaLaRetencionEnDias() {
        var spring = new RegistroKafkaRepository(client, new DynamoDbTables("p-"), 1);
        spring.guardar(new ConsumerRecord<>("t", 0, 0L, "k", "v"));
        var item = capturar().item();
        long expira = Long.parseLong(item.get("expiresAt").n());
        assertThat(expira).isGreaterThan(Instant.now().plus(Duration.ofHours(23)).getEpochSecond());
    }

    private PutItemRequest capturar() {
        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).putItem(captor.capture());
        return captor.getValue();
    }
}
