package com.camisetas360.recommendation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;

/**
 * Cliente DynamoDB (mismo esquema que analytics).
 * - En AWS (ECS Fargate) usa las credenciales del task role (LabRole en AWS Academy).
 * - En local, DYNAMODB_ENDPOINT apunta a DynamoDB Local y se usan credenciales ficticias.
 * Habilita el scheduler del {@code SincronizadorDynamoDb}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.dynamodb.enabled", havingValue = "true", matchIfMissing = true)
public class DynamoDbConfig {

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient(@Value("${app.dynamodb.region:us-east-1}") String region,
                                         @Value("${app.dynamodb.endpoint:}") String endpoint) {
        // Timeouts acotados: el sincronizador mantiene filas bloqueadas mientras escribe.
        var builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(c -> c
                        .apiCallAttemptTimeout(Duration.ofSeconds(3))
                        .apiCallTimeout(Duration.ofSeconds(10)));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }
}
