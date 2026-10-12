package com.camisetas360.orders.messaging;

import com.camisetas360.orders.support.PostgresTestSupport;
import com.camisetas360.orders.service.OrderService;
import com.camisetas360.orders.messaging.event.CheckoutRequestedEvent;
import com.camisetas360.orders.messaging.event.CheckoutItemEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "app.outbox.enabled=false"
})
class OrderOutboxPersistenceIT extends PostgresTestSupport {
    @MockitoBean JwtDecoder decoder;
    @MockitoBean RabbitTemplate rabbit;
    @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test
    void orderAndEventCommitWithoutBroker() {
        var order = orders.createOrder(checkout());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_event_outbox WHERE (payload->>'orderId')::bigint = ?",
                Long.class, order.getId())).isEqualTo(1);
        verifyNoInteractions(rabbit);
    }

    @Test
    void rollbackRetainsNeitherOrderNorEvent() {
        long beforeOrders = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
        long beforeEvents = jdbc.queryForObject("SELECT count(*) FROM order_event_outbox", Long.class);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            orders.createOrder(checkout());
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(beforeOrders);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_event_outbox", Long.class)).isEqualTo(beforeEvents);
    }

    private CheckoutRequestedEvent checkout() {
        return new CheckoutRequestedEvent(UUID.randomUUID(), "buyer@example.test",
                List.of(new CheckoutItemEvent("SKU-A", 1, 20.0)), Instant.now(), java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"));
    }
}
