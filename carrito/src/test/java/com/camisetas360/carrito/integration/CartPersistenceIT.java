package com.camisetas360.carrito.integration;

import com.camisetas360.carrito.dtos.AddCartItemRequest;
import com.camisetas360.carrito.dtos.ShippingRequest;
import com.camisetas360.carrito.model.ShippingMethod;
import com.camisetas360.carrito.repository.CartItemRepository;
import com.camisetas360.carrito.repository.CartShippingRepository;
import com.camisetas360.carrito.security.EntraIdentity;
import com.camisetas360.carrito.service.CartLimitExceededException;
import com.camisetas360.carrito.service.CartManagementService;
import com.camisetas360.carrito.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.camisetas360.carrito.service.CatalogoProductos;
import com.camisetas360.carrito.service.ProductoCatalogo;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration")
class CartPersistenceIT extends PostgresTestSupport {

    private static final EntraIdentity BUYER = new EntraIdentity(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final EntraIdentity OTHER = new EntraIdentity(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), UUID.fromString("33333333-3333-3333-3333-333333333333"));

    @Autowired CartManagementService service;
    @Autowired CartItemRepository items;
    @Autowired CartShippingRepository shipping;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean KafkaTemplate<String, String> kafka;
    @MockitoBean CatalogoProductos catalogo;

    @BeforeEach
    void clean() {
        items.deleteAll();
        shipping.deleteAll();
        @SuppressWarnings("unchecked")
        SendResult<String, String> sent = mock(SendResult.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(sent));
        var productos = Map.of(
                "SKU-A", new ProductoCatalogo("SKU-A", "Camiseta", "Liga A", 20.0),
                "SKU-B", new ProductoCatalogo("SKU-B", "Otra", null, 10.0));
        when(catalogo.buscar(any())).thenAnswer(inv -> {
            Collection<String> skus = inv.getArgument(0);
            return skus.stream().filter(productos::containsKey)
                    .collect(Collectors.toMap(Function.identity(), productos::get));
        });
    }

    @Test
    void cartIsPersistedPerOwnerAndEventsArePublishedAfterCommit() {
        service.agregarItem(BUYER, new AddCartItemRequest("SKU-A", "Camiseta", "Liga A", 20.0, 2));
        service.agregarItem(BUYER, new AddCartItemRequest("SKU-A", "Camiseta", "Liga A", 20.0, 1));
        service.agregarItem(OTHER, new AddCartItemRequest("SKU-B", "Otra", null, 10.0, 1));
        var cart = service.actualizarEnvio(BUYER, new ShippingRequest(ShippingMethod.STANDARD, "Metropolitana", "Santiago"));

        assertThat(cart.items()).singleElement().satisfies(item -> {
            assertThat(item.sku()).isEqualTo("SKU-A");
            assertThat(item.cantidad()).isEqualTo(3);
        });
        assertThat(cart.total()).isEqualTo(60.0 + 3990.0);
        assertThat(service.obtener(OTHER).items()).extracting(i -> i.sku()).containsExactly("SKU-B");

        verify(kafka, times(3)).send(eq("carrito.item-agregado"), anyString(), anyString());
        verify(kafka).send(eq("carrito.envio-actualizado"), eq(BUYER.tenantId() + ":" + BUYER.objectId()), anyString());

        service.eliminarItem(BUYER, "SKU-A");
        assertThat(service.obtener(BUYER).items()).isEmpty();
        verify(kafka).send(eq("carrito.item-eliminado"), anyString(), anyString());
    }

    @Test
    void rejectedChangeIsNotPublished() {
        service.agregarItem(BUYER, new AddCartItemRequest("SKU-A", "Camiseta", null, 20.0, 99));
        clearInvocations((Object) kafka);

        assertThatThrownBy(() -> service.agregarItem(BUYER, new AddCartItemRequest("SKU-A", "Camiseta", null, 20.0, 1)))
                .isInstanceOf(CartLimitExceededException.class);

        verifyNoInteractions(kafka);
        assertThat(items.findAll()).singleElement().satisfies(item -> assertThat(item.getQuantity()).isEqualTo(99));
    }

    @Test
    void databaseConstraintsProtectCartData() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cart_items (tenant_id, object_id, sku, product_name, unit_price, quantity, updated_at)
                VALUES (?, ?, 'SKU-Z', 'X', 10, 100, now())""", BUYER.tenantId(), BUYER.objectId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cart_shipping (tenant_id, object_id, method, region, commune, updated_at)
                VALUES (?, ?, 'DRON', 'R', 'C', now())""", BUYER.tenantId(), BUYER.objectId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
