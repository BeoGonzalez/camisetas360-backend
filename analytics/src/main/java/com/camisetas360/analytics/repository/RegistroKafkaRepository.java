package com.camisetas360.analytics.repository;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Archivo de los mensajes de Kafka (lo mismo que se ve en Kafka UI) en la tabla kafka-registros:
 * PK {@code topic}, SK {@code registro} = {@code <particion>#<offset>} con ceros a la izquierda, asi
 * una particion queda ordenada por offset y una entrega repetida sobrescribe el mismo item (idempotente).
 */
@Repository
public class RegistroKafkaRepository {

    /** DynamoDB admite items de hasta 400 KB; el valor se recorta por debajo de ese limite. */
    static final int MAX_VALOR_BYTES = 350_000;

    private final DynamoDbClient client;
    private final DynamoDbTables tables;
    private final Duration retencion;
    private final Clock clock;

    @Autowired
    public RegistroKafkaRepository(DynamoDbClient client, DynamoDbTables tables,
                                   @Value("${app.kafka.archivo.ttl-dias:90}") long ttlDias) {
        this(client, tables, Duration.ofDays(ttlDias), Clock.systemUTC());
    }

    RegistroKafkaRepository(DynamoDbClient client, DynamoDbTables tables, Duration retencion, Clock clock) {
        this.client = client;
        this.tables = tables;
        this.retencion = retencion;
        this.clock = clock;
    }

    public void guardar(ConsumerRecord<String, String> registro) {
        Instant ahora = clock.instant();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("topic", s(registro.topic()));
        item.put("registro", s(clave(registro.partition(), registro.offset())));
        item.put("particion", n(registro.partition()));
        item.put("offset", n(registro.offset()));
        Instant producido = registro.timestamp() >= 0 ? Instant.ofEpochMilli(registro.timestamp()) : ahora;
        item.put("timestamp", s(producido.toString()));
        item.put("timestampTipo", s(registro.timestampType().name));
        item.put("recibidoEn", s(ahora.toString()));
        if (registro.key() != null) {
            item.put("clave", s(registro.key()));
        }
        if (registro.value() != null) {
            String valor = registro.value();
            byte[] bytes = valor.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_VALOR_BYTES) {
                valor = new String(bytes, 0, MAX_VALOR_BYTES, StandardCharsets.UTF_8);
                item.put("truncado", AttributeValue.builder().bool(true).build());
            }
            item.put("valor", s(valor));
        }
        Map<String, AttributeValue> headers = new HashMap<>();
        for (Header header : registro.headers()) {
            if (header.value() != null) {
                headers.put(header.key(), s(new String(header.value(), StandardCharsets.UTF_8)));
            }
        }
        if (!headers.isEmpty()) {
            item.put("headers", AttributeValue.builder().m(headers).build());
        }
        item.put("expiresAt", n(producido.plus(retencion).getEpochSecond()));

        client.putItem(PutItemRequest.builder().tableName(tables.kafkaRegistros()).item(item).build());
    }

    static String clave(int particion, long offset) {
        return "%05d#%019d".formatted(particion, offset);
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(Number value) {
        return AttributeValue.builder().n(value.toString()).build();
    }
}
