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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CheckoutEventPublisher publisher;

    @InjectMocks
    private CartService service;

    @BeforeEach
    void clearPreviousAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // UT-CART-001
    @Test
    void createOrder_shouldPublishCheckoutRequestedEvent_whenRequestIsValid() {
        authenticate("Buyer@example.test");
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
            List<OrderItemDTO> items, double expectedTotal) {
        authenticate("buyer@example.test");

        var response = service.createOrder(new OrderRequestDTO(items));

        assertThat(response.totalAmount()).isEqualTo(expectedTotal);
        verify(publisher).publishCheckoutRequested(any(CheckoutRequestedEvent.class));
        verifyNoMoreInteractions(publisher);
    }

    static Stream<Arguments> validTotals() {
        return Stream.of(
                Arguments.of(List.of(new OrderItemDTO("A", 2, 19.5),
                        new OrderItemDTO("B", 3, 10.0)), 69.0),
                Arguments.of(List.of(new OrderItemDTO("A", 1, 19.5)), 19.5),
                Arguments.of(List.of(new OrderItemDTO("A", 1, 0.01)), 0.01));
    }

    // UT-CART-003
    @Test
    void createOrder_shouldThrowException_whenPreferredUsernameIsMissing() {
        authenticate(null);

        var failure = assertThrows(IllegalStateException.class,
                () -> service.createOrder(validRequest()));

        assertThat(failure).hasMessage("El token JWT no contiene el claim preferred_username");
        verify(publisher, never()).publishCheckoutRequested(any());
    }

    // UT-CART-004
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void createOrder_shouldNotPublish_whenPreferredUsernameIsBlank(String email) {
        authenticate(email);

        assertThrows(IllegalStateException.class, () -> service.createOrder(validRequest()));

        verifyNoInteractions(publisher);
    }

    // UT-CART-005
    @Test
    void createOrder_shouldPropagateFailure_whenPublisherFails() {
        authenticate("buyer@example.test");
        var failure = new AmqpException("broker unavailable");
        doThrow(failure).when(publisher).publishCheckoutRequested(any());

        assertThat(assertThrows(AmqpException.class,
                () -> service.createOrder(validRequest()))).isSameAs(failure);

        verify(publisher, times(1)).publishCheckoutRequested(any());
        verifyNoMoreInteractions(publisher);
    }

    private static OrderRequestDTO validRequest() {
        return new OrderRequestDTO(List.of(new OrderItemDTO("SKU-A", 1, 19.5)));
    }

    private static void authenticate(String email) {
        var builder = Jwt.withTokenValue("unit-test-token")
                .header("alg", "none")
                .subject("test-user");
        if (email != null) {
            builder.claim("preferred_username", email);
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(builder.build()));
        SecurityContextHolder.setContext(context);
    }
}
