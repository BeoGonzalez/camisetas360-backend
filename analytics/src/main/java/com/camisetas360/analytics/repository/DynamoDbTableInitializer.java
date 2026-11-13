package com.camisetas360.analytics.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;

import java.util.ArrayList;
import java.util.List;

/**
 * Crea las tablas (on-demand / PAY_PER_REQUEST) si no existen, para no depender de pasos manuales
 * en AWS Academy. Un fallo se registra pero no impide el arranque: cada evento se reintenta.
 */
@Component
@ConditionalOnProperty(name = "app.dynamodb.create-tables", havingValue = "true", matchIfMissing = true)
public class DynamoDbTableInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTableInitializer.class);
    private static final StreamSpecification STREAM_NEW_IMAGE = StreamSpecification.builder()
            .streamEnabled(true).streamViewType(StreamViewType.NEW_IMAGE).build();

    private final DynamoDbClient client;
    private final DynamoDbTables tables;
    private final boolean streamProductos;

    public DynamoDbTableInitializer(DynamoDbClient client, DynamoDbTables tables) {
        this(client, tables, true);
    }

    /**
     * @param streamProductos activa DynamoDB Streams (NEW_IMAGE) en la tabla de productos: es la
     *                        fuente de la telemetria en tiempo real (Lambda Stream -> API Gateway WebSocket).
     */
    @Autowired
    public DynamoDbTableInitializer(DynamoDbClient client, DynamoDbTables tables,
                                    @Value("${app.dynamodb.productos-stream:true}") boolean streamProductos) {
        this.client = client;
        this.tables = tables;
        this.streamProductos = streamProductos;
    }

    @Override
    public void run(ApplicationArguments args) {
        crearTablas();
    }

    public void crearTablas() {
        crear(tables.eventos(), "usuarioId", "sk", true, false);
        crear(tables.productos(), "sku", null, false, streamProductos);
        crear(tables.recomendaciones(), "usuarioId", null, false, false);
    }

    private void crear(String tabla, String partitionKey, String sortKey, boolean ttl, boolean stream) {
        try {
            var existente = client.describeTable(request -> request.tableName(tabla)).table();
            log.info("Tabla DynamoDB {} disponible", tabla);
            if (stream) {
                asegurarStream(tabla, existente.streamSpecification());
            }
            return;
        } catch (ResourceNotFoundException missing) {
            log.info("Creando tabla DynamoDB {}", tabla);
        } catch (SdkException failure) {
            log.error("No se pudo verificar la tabla DynamoDB {}: {}", tabla, failure.getMessage());
            return;
        }

        try {
            List<AttributeDefinition> atributos = new ArrayList<>();
            List<KeySchemaElement> claves = new ArrayList<>();
            atributos.add(AttributeDefinition.builder().attributeName(partitionKey).attributeType(ScalarAttributeType.S).build());
            claves.add(KeySchemaElement.builder().attributeName(partitionKey).keyType(KeyType.HASH).build());
            if (sortKey != null) {
                atributos.add(AttributeDefinition.builder().attributeName(sortKey).attributeType(ScalarAttributeType.S).build());
                claves.add(KeySchemaElement.builder().attributeName(sortKey).keyType(KeyType.RANGE).build());
            }
            var peticion = CreateTableRequest.builder()
                    .tableName(tabla)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(atributos)
                    .keySchema(claves);
            if (stream) {
                peticion.streamSpecification(STREAM_NEW_IMAGE);
            }
            client.createTable(peticion.build());
            client.waiter().waitUntilTableExists(request -> request.tableName(tabla));
            if (ttl) {
                client.updateTimeToLive(request -> request.tableName(tabla).timeToLiveSpecification(
                        TimeToLiveSpecification.builder().attributeName("expiresAt").enabled(true).build()));
            }
            log.info("Tabla DynamoDB {} creada", tabla);
        } catch (SdkException failure) {
            log.error("No se pudo crear la tabla DynamoDB {}: {}", tabla, failure.getMessage());
        }
    }

    /** Si la tabla ya existia sin stream (p. ej. creada antes de esta version), lo activa. */
    private void asegurarStream(String tabla, StreamSpecification actual) {
        if (actual != null && Boolean.TRUE.equals(actual.streamEnabled())) {
            if (actual.streamViewType() != StreamViewType.NEW_IMAGE
                    && actual.streamViewType() != StreamViewType.NEW_AND_OLD_IMAGES) {
                log.warn("La tabla {} tiene un stream {} sin imagen nueva: la telemetria no recibira datos",
                        tabla, actual.streamViewType());
            }
            return;
        }
        try {
            client.updateTable(request -> request.tableName(tabla).streamSpecification(STREAM_NEW_IMAGE));
            client.waiter().waitUntilTableExists(request -> request.tableName(tabla));
            log.info("DynamoDB Streams (NEW_IMAGE) activado en {}", tabla);
        } catch (SdkException failure) {
            log.error("No se pudo activar DynamoDB Streams en {}: {}", tabla, failure.getMessage());
        }
    }
}
