package com.camisetas360.testing.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CheckoutFlowE2EIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String FROM = "orders@camisetas360.test";

    private static final String CUSTOMER_ROLE = "CUSTOMER";

    private static final String ITEMS = """
            [
              {
                "sku": "CAM-Ñ-東京",
                "quantity": 2,
                "unitPrice": 19.5
              },
              {
                "sku": "CAM-002",
                "quantity": 3,
                "unitPrice": 10.0
              }
            ]
            """;

    private final List<AutoCloseable> resources = new ArrayList<>();

    private HttpClient http;
    private RabbitMQContainer rabbit;
    private GenericContainer<?> smtp;
    private TestJwtIssuer issuer;
    private ServiceProcess carrito;
    private ServiceProcess orders;
    private ServiceProcess notifications;
    private Path logs;

    @SuppressWarnings("resource")
    @BeforeEach
    void startIsolatedSystem(
            TestInfo info) throws Exception {

        var root = Path.of(
                System.getProperty("repo.root"))
                .toAbsolutePath()
                .normalize();

        logs = Path.of(
                System.getProperty("e2e.logs"))
                .resolve(
                        info.getTestMethod()
                                .orElseThrow()
                                .getName()
                                + "-"
                                + UUID.randomUUID());

        Files.createDirectories(logs);

        http = register(
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofSeconds(5))
                        .build());

        rabbit = register(
                new RabbitMQContainer(
                        "rabbitmq:4-management"));

        rabbit.start();

        var mailpit = new GenericContainer<>(
                DockerImageName.parse(
                        "axllent/mailpit:v1.31.3"))
                .withExposedPorts(
                        1025,
                        8025)
                .waitingFor(
                        Wait.forHttp(
                                "/api/v1/messages")
                                .forPort(8025))
                .withStartupTimeout(
                        Duration.ofSeconds(60));

        smtp = register(mailpit);

        smtp.start();

        issuer = register(
                new TestJwtIssuer());

        var common = new HashMap<String, String>();

        common.put(
                "spring.rabbitmq.host",
                rabbit.getHost());

        common.put(
                "spring.rabbitmq.port",
                rabbit.getAmqpPort().toString());

        common.put(
                "spring.rabbitmq.username",
                rabbit.getAdminUsername());

        common.put(
                "spring.rabbitmq.password",
                rabbit.getAdminPassword());

        common.put(
                "spring.rabbitmq.virtual-host",
                "/");

        common.put(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                issuer.issuer());

        common.put(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                issuer.jwks());

        common.put(
                "spring.security.oauth2.resourceserver.jwt.audiences[0]",
                TestJwtIssuer.AUDIENCE);

        carrito = register(
                new ServiceProcess(
                        root,
                        logs,
                        "carrito",
                        common));

        var orderProperties = new HashMap<>(common);

        orderProperties.put(
                "spring.datasource.url",
                "jdbc:h2:mem:e2e_"
                        + UUID.randomUUID());

        orderProperties.put(
                "spring.jpa.hibernate.ddl-auto",
                "create-drop");

        orders = register(
                new ServiceProcess(
                        root,
                        logs,
                        "orders",
                        orderProperties));

        var notificationProperties = new HashMap<>(common);

        notificationProperties.putAll(
                Map.of(
                        "spring.mail.host",
                        smtp.getHost(),

                        "spring.mail.port",
                        smtp.getMappedPort(1025)
                                .toString(),

                        "spring.mail.username",
                        "e2e",

                        "spring.mail.password",
                        "e2e",

                        "spring.mail.properties.mail.smtp.auth",
                        "false",

                        "spring.mail.properties.mail.smtp.starttls.enable",
                        "false",

                        "spring.mail.properties.mail.smtp.starttls.required",
                        "false",

                        "app.mail.from",
                        FROM));

        for (var timeout : List.of(
                "connectiontimeout",
                "timeout",
                "writetimeout")) {
            notificationProperties.put(
                    "spring.mail.properties.mail.smtp."
                            + timeout,
                    "5000");
        }

        notifications = register(
                new ServiceProcess(
                        root,
                        logs,
                        "notifications",
                        notificationProperties));

        carrito.awaitStarted();
        orders.awaitStarted();
        notifications.awaitStarted();

        assertThat(
                request(
                        "POST",
                        carrito.baseUrl()
                                + "/api/v1/carrito/checkout",
                        null,
                        "{}").statusCode())
                .as(
                        "Checkout is protected in the actual running service")
                .isEqualTo(401);

        var readinessToken = issuer.token(
                "readiness@example.test",
                "Orders.Read",
                CUSTOMER_ROLE);

        assertThat(
                getJson(
                        orders.baseUrl()
                                + "/api/v1/orders",
                        readinessToken,
                        200).size())
                .isZero();

        await()
                .alias(
                        "Listeners and SMTP are ready")
                .atMost(
                        Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {

                            assertQueueHasConsumer(
                                    "orders.checkout-requested.q");

                            assertQueueHasConsumer(
                                    "notifications.order-created.q");

                            assertThat(
                                    getJson(
                                            notifications.baseUrl()
                                                    + "/actuator/health",
                                            null,
                                            200)
                                            .get("status")
                                            .asString())
                                    .isEqualTo(
                                            "UP");
                        });

        assertThat(
                getJson(
                        mailApi()
                                + "/messages",
                        null,
                        200)
                        .get("messages")
                        .size())
                .isZero();
    }

    @Test
    void checkout_shouldPersistOrderAndDeliverEmail_whenRequestIsValid()
            throws Exception {

        checkoutAndAssertDelivery();
    }

    @Test
    void getOrder_shouldReturn404ForAnotherUser_whenCheckoutBelongsToOwner()
            throws Exception {

        var flow = checkoutAndAssertDelivery();

        var otherToken = issuer.token(
                "other-"
                        + UUID.randomUUID()
                        + "@example.test",
                "Orders.Read",
                CUSTOMER_ROLE);

        var denied = getJson(
                orders.baseUrl()
                        + "/api/v1/orders/"
                        + flow.orderId(),
                otherToken,
                404);

        assertThat(
                denied.get("status")
                        .asInt())
                .isEqualTo(404);

        assertThat(
                denied.get("error")
                        .asString())
                .isEqualTo(
                        "Not Found");

        assertThat(
                denied.has("items"))
                .isFalse();

        assertThat(
                denied.has("userEmail"))
                .isFalse();

        assertThat(
                getJson(
                        orders.baseUrl()
                                + "/api/v1/orders",
                        otherToken,
                        200).size())
                .isZero();

        assertThat(
                getJson(
                        orders.baseUrl()
                                + "/api/v1/orders/"
                                + flow.orderId(),
                        flow.token(),
                        200))
                .isEqualTo(
                        flow.order());

        save(
                "foreign-order-response.json",
                denied);
    }

    private Flow checkoutAndAssertDelivery()
            throws Exception {

        var email = "buyer-"
                + UUID.randomUUID()
                + "@example.test";

        var token = issuer.token(
                email,
                "Checkout.Create Orders.Read",
                CUSTOMER_ROLE);

        var checkout = request(
                "POST",
                carrito.baseUrl()
                        + "/api/v1/carrito/checkout",
                token,
                "{\"items\":"
                        + ITEMS
                        + "}");

        assertThat(
                checkout.statusCode())
                .as(
                        checkout.body())
                .isEqualTo(202);

        var accepted = JSON.readTree(
                checkout.body());

        assertThat(
                accepted.get("status")
                        .asString())
                .isEqualTo(
                        "PROCESSING");

        assertThat(
                accepted.get("userEmail")
                        .asString())
                .isEqualTo(
                        email);

        assertThat(
                accepted.get("totalAmount")
                        .asDouble())
                .isEqualTo(
                        69.0);

        assertThat(
                UUID.fromString(
                        accepted.get("requestId")
                                .asString()))
                .isNotNull();

        save(
                "checkout-response.json",
                accepted);

        var listUrl = orders.baseUrl()
                + "/api/v1/orders";

        await()
                .alias(
                        "Order becomes visible to its owner")
                .atMost(
                        Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {

                            var found = getJson(
                                    listUrl,
                                    token,
                                    200);

                            assertThat(
                                    found.size())
                                    .isEqualTo(
                                            1);

                            assertOrder(
                                    found.get(0),
                                    email);
                        });

        var order = getJson(
                listUrl,
                token,
                200)
                .get(0);

        long id = order.get("orderId")
                .asLong();

        assertThat(
                getJson(
                        listUrl
                                + "/"
                                + id,
                        token,
                        200))
                .isEqualTo(
                        order);

        save(
                "order-response.json",
                order);

        await()
                .alias(
                        "Notification arrives over SMTP")
                .atMost(
                        Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {

                            var messages = getJson(
                                    mailApi()
                                            + "/messages",
                                    null,
                                    200)
                                    .get(
                                            "messages");

                            assertThat(
                                    messages.size())
                                    .isEqualTo(
                                            1);

                            var message = getJson(
                                    mailApi()
                                            + "/message/"
                                            + messages
                                                    .get(0)
                                                    .get("ID")
                                                    .asString(),
                                    null,
                                    200);

                            assertThat(
                                    message.get("From")
                                            .get("Address")
                                            .asString())
                                    .isEqualTo(
                                            FROM);

                            assertThat(
                                    message.get("To")
                                            .size())
                                    .isEqualTo(
                                            1);

                            assertThat(
                                    message.get("To")
                                            .get(0)
                                            .get("Address")
                                            .asString())
                                    .isEqualTo(
                                            email);

                            assertThat(
                                    message.get("Subject")
                                            .asString())
                                    .isEqualTo(
                                            "Orden creada #"
                                                    + id);

                            assertThat(
                                    message.get("Text")
                                            .asString()
                                            .replace(
                                                    "\r\n",
                                                    "\n"))
                                    .isEqualTo(
                                            """
                                                    Hola,

                                                    Tu orden fue creada correctamente.

                                                    Número de orden: %s
                                                    Total: $69.00
                                                    Estado: CREATED

                                                    Gracias por comprar en Camisetas360.
                                                    """.formatted(id));

                            save(
                                    "delivered-email.json",
                                    message);
                        });

        return new Flow(
                id,
                token,
                order);
    }

    private void assertOrder(
            JsonNode order,
            String email) throws Exception {

        assertThat(
                order.get("orderId")
                        .asLong())
                .isPositive();

        assertThat(
                order.get("userEmail")
                        .asString())
                .isEqualTo(
                        email);

        assertThat(
                order.get("totalAmount")
                        .asDouble())
                .isEqualTo(
                        69.0);

        assertThat(
                order.get("status")
                        .asString())
                .isEqualTo(
                        "CREATED");

        assertThat(
                Instant.parse(
                        order.get("createdAt")
                                .asString()))
                .isNotNull();

        var items = new ArrayList<JsonNode>();

        order.get("items")
                .forEach(
                        items::add);

        var expected = JSON.readTree(
                ITEMS);

        assertThat(
                items)
                .containsExactlyInAnyOrder(
                        expected.get(0),
                        expected.get(1));
    }

    private void assertQueueHasConsumer(
            String queue) throws Exception {

        var credentials = rabbit.getAdminUsername()
                + ":"
                + rabbit.getAdminPassword();

        var request = HttpRequest.newBuilder(
                URI.create(
                        "http://"
                                + rabbit.getHost()
                                + ":"
                                + rabbit.getHttpPort()
                                + "/api/queues/%2F/"
                                + queue))
                .timeout(
                        Duration.ofSeconds(10))
                .header(
                        "Authorization",
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                credentials
                                                        .getBytes(
                                                                StandardCharsets.UTF_8)))
                .GET()
                .build();

        var response = http.send(
                request,
                HttpResponse.BodyHandlers
                        .ofString());

        assertThat(
                response.statusCode())
                .as(
                        "Queue " + queue)
                .isEqualTo(
                        200);

        var consumers = JSON.readTree(
                response.body())
                .get(
                        "consumers");

        assertThat(
                consumers)
                .as(
                        "Consumer statistics available for "
                                + queue)
                .isNotNull();

        assertThat(
                consumers.asInt())
                .isGreaterThanOrEqualTo(
                        1);
    }

    private HttpResponse<String> request(
            String method,
            String url,
            String token,
            String body) throws Exception {

        var builder = HttpRequest.newBuilder(
                URI.create(url))
                .timeout(
                        Duration.ofSeconds(10));

        if (token != null) {
            builder.header(
                    "Authorization",
                    "Bearer " + token);
        }

        if (body != null) {
            builder.header(
                    "Content-Type",
                    "application/json");
        }

        return http.send(
                builder.method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers
                                        .noBody()
                                : HttpRequest.BodyPublishers
                                        .ofString(body))
                        .build(),
                HttpResponse.BodyHandlers
                        .ofString());
    }

    private JsonNode getJson(
            String url,
            String token,
            int expectedStatus) throws Exception {

        var response = request(
                "GET",
                url,
                token,
                null);

        assertThat(
                response.statusCode())
                .as(
                        "GET "
                                + url
                                + ": "
                                + response.body())
                .isEqualTo(
                        expectedStatus);

        return JSON.readTree(
                response.body());
    }

    private String mailApi() {
        return "http://"
                + smtp.getHost()
                + ":"
                + smtp.getMappedPort(8025)
                + "/api/v1";
    }

    private void save(
            String name,
            JsonNode value) throws Exception {

        Files.writeString(
                logs.resolve(name),
                JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(
                                value));
    }

    private <T extends AutoCloseable> T register(
            T resource) {

        resources.add(
                resource);

        return resource;
    }

    @AfterEach
    void stopIsolatedSystem()
            throws Exception {

        Exception failure = null;

        try {

            if (rabbit != null
                    && rabbit.isRunning()) {

                Files.writeString(
                        logs.resolve(
                                "rabbitmq.log"),
                        rabbit.getLogs());
            }

            if (smtp != null
                    && smtp.isRunning()) {

                Files.writeString(
                        logs.resolve(
                                "smtp.log"),
                        smtp.getLogs());
            }

        } catch (Exception exception) {

            failure = exception;
        }

        for (var resource : resources.reversed()) {

            try {

                resource.close();

            } catch (Exception exception) {

                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(
                            exception);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private record Flow(
            long orderId,
            String token,
            JsonNode order) {
    }
}