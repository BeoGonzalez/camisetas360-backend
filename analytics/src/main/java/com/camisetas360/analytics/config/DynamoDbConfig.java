package com.camisetas360.analytics.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/**
 * Cliente DynamoDB.
 * - En AWS (ECS Fargate) usa las credenciales del task role (LabRole en AWS Academy).
 * - En local, DYNAMODB_ENDPOINT apunta a DynamoDB Local y se usan credenciales ficticias.
 */
@Configuration
public class DynamoDbConfig {

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient(@Value("${app.dynamodb.region:us-east-1}") String region,
                                         @Value("${app.dynamodb.endpoint:}") String endpoint) {
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }
}
