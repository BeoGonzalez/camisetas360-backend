package com.camisetas360.recommendation.repository;

import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.InteraccionPendiente;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Escribe interacciones en la tabla de la guia ({@code UserInteractions}: clave de particion
 * {@code userId}, clave de ordenacion {@code timestamp}, ambas String). Los nombres de atributos
 * siguen el JSON de la guia (itemId, action, category) para que el servicio de TensorFlow los lea.
 */
@Repository
@ConditionalOnProperty(name = "app.dynamodb.enabled", havingValue = "true", matchIfMissing = true)
public class DynamoInteraccionRepository {

    /** Maximo de BatchWriteItem. */
    public static final int LOTE_MAXIMO = 25;
    private static final int REINTENTOS_NO_PROCESADOS = 3;

    // Ancho fijo (siempre milisegundos y Z): el orden lexicografico coincide con el cronologico.
    private static final DateTimeFormatter INSTANTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final DynamoDbClient client;
    private final String tabla;

    public DynamoInteraccionRepository(DynamoDbClient client,
                                       @Value("${app.dynamodb.tabla-interacciones:UserInteractions}") String tabla) {
        this.client = Objects.requireNonNull(client, "client");
        this.tabla = Objects.requireNonNull(tabla, "tabla");
    }

    /**
     * Escribe el lote con BatchWriteItem (PutItem idempotente: la clave deriva del eventId, asi que
     * reenviar la misma interaccion sobrescribe el mismo item).
     *
     * @return eventIds que DynamoDB no proceso tras los reintentos (UnprocessedItems)
     */
    public Set<UUID> guardar(List<InteraccionPendiente> lote) {
        if (lote.size() > LOTE_MAXIMO) {
            throw new IllegalArgumentException("BatchWriteItem admite hasta " + LOTE_MAXIMO + " items");
        }
        List<WriteRequest> pendientes = lote.stream()
                .map(i -> WriteRequest.builder().putRequest(PutRequest.builder().item(item(i)).build()).build())
                .toList();
        for (int intento = 0; !pendientes.isEmpty(); intento++) {
            var respuesta = client.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(tabla, pendientes)).build());
            pendientes = respuesta.unprocessedItems().getOrDefault(tabla, List.of());
            if (pendientes.isEmpty() || intento == REINTENTOS_NO_PROCESADOS) break;
            dormir(100L << intento);
        }
        Set<UUID> noProcesados = new HashSet<>();
        for (WriteRequest request : pendientes) {
            noProcesados.add(UUID.fromString(request.putRequest().item().get("eventId").s()));
        }
        return noProcesados;
    }

    public static Map<String, AttributeValue> item(InteraccionPendiente i) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", s(i.usuarioId()));
        // Instante + eventId: dos eventos del mismo usuario en el mismo milisegundo no se pisan.
        item.put("timestamp", s(timestamp(i.ocurridoEn(), i.eventId())));
        item.put("eventId", s(i.eventId().toString()));
        item.put("itemId", s(i.sku()));
        item.put("action", s(accionGuia(i.accion())));
        item.put("weight", n(Integer.toString(i.peso())));
        item.put("source", s(i.origen()));
        item.put("occurredAt", s(INSTANTE.format(i.ocurridoEn())));
        item.put("occurredAtEpochMs", n(Long.toString(i.ocurridoEn().toEpochMilli())));
        if (presente(i.categoria())) item.put("category", s(i.categoria()));
        if (presente(i.nombre())) item.put("name", s(i.nombre()));
        if (i.precio() != null) item.put("price", n(BigDecimal.valueOf(i.precio()).toPlainString()));
        return item;
    }

    static String timestamp(Instant ocurridoEn, UUID eventId) {
        return INSTANTE.format(ocurridoEn) + "#" + eventId;
    }

    /** Mismos valores que el campo action de la guia: view (1), cart (2), purchase (3). */
    static String accionGuia(Accion accion) {
        return switch (accion) {
            case VISTA -> "view";
            case CARRITO -> "cart";
            case COMPRA -> "purchase";
        };
    }

    private static boolean presente(String valor) {
        return valor != null && !valor.isBlank();
    }

    private static AttributeValue s(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    private static AttributeValue n(String valor) {
        return AttributeValue.builder().n(valor).build();
    }

    private static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
        }
    }
}
