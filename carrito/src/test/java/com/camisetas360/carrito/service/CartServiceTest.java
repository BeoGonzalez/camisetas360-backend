package com.camisetas360.carrito.service;

import com.camisetas360.carrito.dtos.OrderItemDTO;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.messaging.CheckoutEventPublisher;
import com.camisetas360.carrito.messaging.event.CheckoutItemEvent;
import com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import org.springframework.transaction.support.TransactionOperations;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CheckoutEventPublisher publisher;

    @Mock
    private com.camisetas360.carrito.repository.CheckoutRequestRepository checkouts;

    @Mock
    private CatalogoProductos catalogo;

    private CartService service;

    @BeforeEach
    void clearPreviousAuthentication() {
        SecurityContextHolder.clearContext();
        service = new CartService(publisher, checkouts, catalogo, TransactionOperations.withoutTransaction());
    }

    /** El catalogo responde con estos precios; los SKU ausentes del mapa no existen. */
    private void catalogoConPrecios(Map<String, Double> precios) {
        lenient().when(catalogo.buscar(any())).thenAnswer(inv -> {
            Collection<String> skus = inv.getArgument(0);
            var encontrados = new LinkedHashMap<String, ProductoCatalogo>();
            skus.stream().filter(precios::containsKey)
                    .forEach(sku -> encontrados.put(sku, new ProductoCatalogo(sku, "Producto " + sku, null, precios.get(sku))));
            return encontrados;
        });
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // UT-CART-001
    @Test
    void createOrder_shouldPublishCheckoutRequestedEvent_whenRequestIsValid() {
        authenticate("Buyer@example.test");
        catalogoConPrecios(Map.of("SKU-A", 19.5, "SKU-B", 10.0));
        var request = new OrderRequestDTO(List.of(
                new OrderItemDTO("SKU-A", 2, 19.5),
                new OrderItemDTO("SKU-B", 3, 10.0)));

        var response = service.createOrder(request);

        var captor = ArgumentCaptor.forClass(CheckoutRequestedEvent.class);
        verify(publisher, times(1)).publishCheckoutRequested(captor.capture());
        verifyNoMoreInteractions(publisher);
        var event = captor.getValue();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.userEmail()).isEqualTo("Buyer@example.test");
        assertThat(event).extracting("tenantId","objectId").containsExactly(
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"));
        assertThat(event.items()).containsExactly(
                new CheckoutItemEvent("SKU-A", 2, 19.5),
                new CheckoutItemEvent("SKU-B", 3, 10.0));
        assertThat(response.requestId()).isEqualTo(event.eventId());
        assertThat(response.userEmail()).isEqualTo(event.userEmail());
        assertThat(response.totalAmount()).isEqualTo(69.0);
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(request.items()).containsExactly(
                new OrderItemDTO("SKU-A", 2, 19.5),
                new OrderItemDTO("SKU-B", 3, 10.0));
    }

    // UT-CART-002
    @ParameterizedTest
    @MethodSource("validTotals")
    void createOrder_shouldCalculateTotal_whenItemsHaveDifferentQuantities(
            List<OrderItemDTO> items, Map<String, Double> preciosCatalogo, double expectedTotal) {
        authenticate("buyer@example.test");
        catalogoConPrecios(preciosCatalogo);

        var response = service.createOrder(new OrderRequestDTO(items));

        assertThat(response.totalAmount()).isEqualTo(expectedTotal);
        verify(publisher).publishCheckoutRequested(any(CheckoutRequestedEvent.class));
        verifyNoMoreInteractions(publisher);
    }

    static Stream<Arguments> validTotals() {
        return Stream.of(
                Arguments.of(List.of(new OrderItemDTO("A", 2, 19.5),
                        new OrderItemDTO("B", 3, 10.0)), Map.of("A", 19.5, "B", 10.0), 69.0),
                Arguments.of(List.of(new OrderItemDTO("A", 1, 19.5)), Map.of("A", 19.5), 19.5),
                // El precio enviado por el cliente se ignora: manda el catalogo.
                Arguments.of(List.of(new OrderItemDTO("A", 1, 0.01)), Map.of("A", 49.99), 49.99));
    }

    // UT-CART-003
    @Test
    void createOrder_shouldThrowException_whenPreferredUsernameIsMissing() {
        authenticate(null);

        var failure = assertThrows(MissingContactEmailException.class,
                () -> service.createOrder(validRequest()));

        assertThat(failure).hasMessage("A valid contact email is required for checkout notifications");
        verify(publisher, never()).publishCheckoutRequested(any());
        verifyNoInteractions(catalogo);
    }

    // UT-CART-004
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void createOrder_shouldNotPublish_whenPreferredUsernameIsBlank(String email) {
        authenticate(email);

        assertThrows(MissingContactEmailException.class, () -> service.createOrder(validRequest()));

        verifyNoInteractions(publisher);
    }

    // UT-CART-005
    @Test
    void createOrder_shouldPropagateFailure_whenPublisherFails() {
        authenticate("buyer@example.test");
        catalogoConPrecios(Map.of("SKU-A", 19.5));
        var failure = new AmqpException("broker unavailable");
        doThrow(failure).when(publisher).publishCheckoutRequested(any());

        assertThat(assertThrows(AmqpException.class,
                () -> service.createOrder(validRequest()))).isSameAs(failure);

        verify(publisher, times(1)).publishCheckoutRequested(any());
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void missingUsernameCanUseContactEmailClaim() {
        authenticate(null);
        catalogoConPrecios(Map.of("SKU-A", 19.5));
        var original=(Jwt)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var jwt=Jwt.withTokenValue("test").header("alg","none")
                .claims(claims->claims.putAll(original.getClaims())).claim("email","contact@example.test").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        assertThat(service.createOrder(validRequest()).userEmail()).isEqualTo("contact@example.test");
    }

    // UT-CART-006
    @Test
    void createOrder_shouldUseCatalogPrices_ignoringClientPrices() {
        authenticate("buyer@example.test");
        catalogoConPrecios(Map.of("CAM-003", 49.99));

        var response = service.createOrder(new OrderRequestDTO(List.of(new OrderItemDTO("CAM-003", 2, 0.01))));

        var captor = ArgumentCaptor.forClass(CheckoutRequestedEvent.class);
        verify(publisher).publishCheckoutRequested(captor.capture());
        assertThat(captor.getValue().items()).containsExactly(new CheckoutItemEvent("CAM-003", 2, 49.99));
        assertThat(response.totalAmount()).isEqualTo(99.98);
        verify(checkouts).saveAndFlush(any());
    }

    // UT-CART-007
    @Test
    void createOrder_shouldRejectUnknownSkuWithoutPersistingOrPublishing() {
        authenticate("buyer@example.test");
        catalogoConPrecios(Map.of("SKU-A", 19.5));

        var failure = assertThrows(ProductoNoDisponibleException.class, () -> service.createOrder(new OrderRequestDTO(
                List.of(new OrderItemDTO("SKU-A", 1, 19.5), new OrderItemDTO("NO-EXISTE", 1, 10.0)))));

        assertThat(failure.getMessage()).contains("NO-EXISTE");
        verifyNoInteractions(publisher, checkouts);
    }

    // UT-CART-008
    @Test
    void createOrder_shouldFailWithoutPersisting_whenCatalogIsUnavailable() {
        authenticate("buyer@example.test");
        when(catalogo.buscar(any())).thenThrow(new CatalogoNoDisponibleException(new RuntimeException("timeout")));

        assertThrows(CatalogoNoDisponibleException.class, () -> service.createOrder(validRequest()));

        verifyNoInteractions(publisher, checkouts);
    }

    // UT-CART-009
    @Test
    void createOrder_shouldQueryCatalogOnceWithDistinctSkus() {
        authenticate("buyer@example.test");
        catalogoConPrecios(Map.of("SKU-A", 19.5, "SKU-B", 10.0));

        service.createOrder(new OrderRequestDTO(List.of(new OrderItemDTO("SKU-A", 1, 1.0),
                new OrderItemDTO("SKU-B", 1, 1.0), new OrderItemDTO("SKU-A", 2, 1.0))));

        verify(catalogo, times(1)).buscar(List.of("SKU-A", "SKU-B"));
    }

    private static OrderRequestDTO validRequest() {
        return new OrderRequestDTO(List.of(new OrderItemDTO("SKU-A", 1, 19.5)));
    }

    private static void authenticate(String email) {
        var builder = Jwt.withTokenValue("unit-test-token")
                .header("alg", "none")
                .subject("test-user")
                .claim("tid","11111111-1111-1111-1111-111111111111")
                .claim("oid","22222222-2222-2222-2222-222222222222");
        if (email != null) {
            builder.claim("preferred_username", email);
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(builder.build()));
        SecurityContextHolder.setContext(context);
    }
}
