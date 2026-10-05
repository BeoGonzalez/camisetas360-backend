package com.camisetas360.carrito.integration;

import com.camisetas360.carrito.dtos.OrderItemDTO;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.repository.CheckoutRequestRepository;
import com.camisetas360.carrito.service.CartService;
import com.camisetas360.carrito.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
class CheckoutPersistenceIT extends PostgresTestSupport {
    @Autowired CartService service;
    @Autowired CheckoutRequestRepository checkouts;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void initialize() {
        checkouts.deleteAll();
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("buyer")
                .claim("preferred_username", "buyer@example.test").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test void checkoutCommitsTheExactRequestAndItsRelationships() {
        Instant before = Instant.now();
        var response = service.createOrder(request());
        assertThat(response.totalAmount()).isEqualTo(69.0);
        assertThat(response.status()).isEqualTo("PROCESSING");
        var stored = checkouts.findById(response.requestId()).orElseThrow();
        assertThat(stored.getUserEmail()).isEqualTo("buyer@example.test");
        assertThat(stored.getTotalAmount()).isEqualTo(69.0);
        assertThat(stored.getStatus()).isEqualTo("PROCESSING");
        assertThat(stored.getCreatedAt()).isBetween(before.minusNanos(1000), Instant.now());
        assertThat(stored.getItems()).extracting(item -> List.of(item.getSku(), item.getQuantity(), item.getUnitPrice()))
                .containsExactlyInAnyOrder(List.of("CAM-Ñ-東京", 2, 19.5), List.of("CAM-002", 3, 10.0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checkout_request_items", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT id FROM checkout_requests", UUID.class)).isEqualTo(response.requestId());
    }

    @Test void brokerFailureRollsBackTheAlreadyFlushedRequestAndItems() {
        doThrow(new AmqpException("broker unavailable")).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));
        assertThatThrownBy(() -> service.createOrder(request())).isInstanceOf(AmqpException.class);
        assertThat(checkouts.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checkout_request_items", Integer.class)).isZero();
    }

    @Test void invalidItemFailsRealConstraintsBeforePublicationAndRollsBackTheParent() {
        var invalid = new OrderRequestDTO(List.of(new OrderItemDTO("invalid", 0, 10.0)));
        assertThatThrownBy(() -> service.createOrder(invalid)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(checkouts.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checkout_request_items", Integer.class)).isZero();
        verifyNoInteractions(rabbit);
    }

    @Test void flywayCreatesTheUuidKeyForeignKeyAndRequiredFields() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='checkout_requests' AND column_name='id'", String.class)).isEqualTo("uuid");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO checkout_request_items (checkout_id,sku,quantity,unit_price) VALUES (?, 'missing', 1, 10)", UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO checkout_requests (id) VALUES (?)", UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='ix_checkout_request_items_checkout'", Integer.class)).isEqualTo(1);
    }

    private static OrderRequestDTO request() {
        return new OrderRequestDTO(List.of(new OrderItemDTO("CAM-Ñ-東京", 2, 19.5), new OrderItemDTO("CAM-002", 3, 10.0)));
    }
}
