package com.camisetas360.recommendation.messaging;

import com.camisetas360.recommendation.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@ActiveProfiles("messaging")
@SpringBootTest(properties = {
        "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true"
})
class RabbitDeliveryIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL", POSTGRES::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", POSTGRES::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD", POSTGRES::getPassword);
        registry.add("RABBITMQ_HOST", RABBIT::getHost);
        registry.add("RABBITMQ_PORT", RABBIT::getAmqpPort);
        registry.add("RABBITMQ_USERNAME", RABBIT::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", RABBIT::getAdminPassword);
        registry.add("RABBITMQ_VHOST", () -> "/");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderEventInbox inbox;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired ConfigurableApplicationContext context;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recommendation_order_inbox");
        while (rabbit.receive(RabbitMQConfig.DLQ) != null) { }
    }

    @Test
    void independentlyRoutesToNotificationsAndDeduplicatesRecommendation() throws Exception {
        var admin = new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit.getConnectionFactory());
        var notification = QueueBuilder.durable("notifications.order-created.q").build();
        admin.declareQueue(notification);
        admin.declareBinding(BindingBuilder.bind(notification)
                .to(new DirectExchange("camisetas360.orders", true, false)).with("order.created"));
        admin.purgeQueue(notification.getName());
        var id = UUID.randomUUID();
        byte[] body = event(id);
        publish(body);
        publish(body);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_order_inbox", Long.class)).isEqualTo(1));
        assertThat(rabbit.receive(notification.getName(), 5000)).isNotNull();
        assertThat(rabbit.receive(notification.getName(), 5000)).isNotNull();
        assertThat(inbox.accept(body)).isFalse();
    }

    @Test
    void invalidEventIsDeadLettered() throws Exception {
        publish("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(rabbit.receive(RabbitMQConfig.DLQ, 15000)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_order_inbox", Long.class)).isZero();
    }

    @Test
    void consumerReconnectsAfterBrokerOutageWithoutRestartingApplication() throws Exception {
        assertThat(RABBIT.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        try {
            assertThat(context.isActive()).isTrue();
        } finally {
            assertThat(RABBIT.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(listeners.getListenerContainers()).allSatisfy(container ->
                        assertThat(((SimpleMessageListenerContainer) container).getActiveConsumerCount()).isPositive()));
        publish(event(UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_order_inbox", Long.class)).isEqualTo(1));
    }

    private void publish(byte[] body) throws Exception {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send("camisetas360.orders", "order.created", new Message(body, properties), correlation);
        assertThat(correlation.getFuture().get(10, TimeUnit.SECONDS).ack()).isTrue();
        assertThat(correlation.getReturned()).isNull();
    }

    private byte[] event(UUID id) {
        return ("{\"eventId\":\"" + id + "\",\"orderId\":42,\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":20.0}]}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
