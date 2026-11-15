package com.camisetas360.recommendation.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Crea la tabla de la guia si no existe: {@code userId} (particion) + {@code timestamp}
 * (ordenacion), ambas String, on-demand. Si ya fue creada a mano desde la consola, solo la verifica.
 * Un fallo se registra pero no impide el arranque: el sincronizador reintenta.
 */
@Component
@ConditionalOnProperty(prefix = "app.dynamodb", name = {"enabled", "create-tables"}, havingValue = "true",
        matchIfMissing = true)
public class DynamoDbTablaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTablaInitializer.class);

    private final DynamoDbClient client;
    private final String tabla;

    public DynamoDbTablaInitializer(DynamoDbClient client,
                                    @Value("${app.dynamodb.tabla-interacciones:UserInteractions}") String tabla) {
        this.client = client;
        this.tabla = tabla;
    }

    @Override
    public void run(ApplicationArguments args) {
        crearTabla();
    }

    public void crearTabla() {
        try {
            client.describeTable(r -> r.tableName(tabla));
            log.info("Tabla DynamoDB {} disponible", tabla);
            return;
        } catch (ResourceNotFoundException faltante) {
            log.info("Creando tabla DynamoDB {}", tabla);
        } catch (SdkException fallo) {
            log.error("No se pudo verificar la tabla DynamoDB {}: {}", tabla, fallo.getMessage());
            return;
        }
        try {
            client.createTable(r -> r.tableName(tabla)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("timestamp").attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("timestamp").keyType(KeyType.RANGE).build()));
            client.waiter().waitUntilTableExists(r -> r.tableName(tabla));
            log.info("Tabla DynamoDB {} creada", tabla);
        } catch (SdkException fallo) {
            log.error("No se pudo crear la tabla DynamoDB {}: {}", tabla, fallo.getMessage());
        }
    }
}
