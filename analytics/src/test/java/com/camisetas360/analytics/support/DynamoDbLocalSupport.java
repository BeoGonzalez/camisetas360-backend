package com.camisetas360.analytics.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/** DynamoDB Local en Testcontainers (sin credenciales de AWS reales). */
public final class DynamoDbLocalSupport {

    private static GenericContainer<?> container;

    private DynamoDbLocalSupport() {
    }

    @SuppressWarnings("resource")
    public static synchronized String endpoint() {
        if (container == null || !container.isRunning()) {
            container = new GenericContainer<>("amazon/dynamodb-local:2.5.2")
                    .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
                    .withExposedPorts(8000)
                    .waitingFor(Wait.forListeningPort());
            container.start();
        }
        return "http://" + container.getHost() + ":" + container.getMappedPort(8000);
    }

    public static DynamoDbClient client() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
    }
}
