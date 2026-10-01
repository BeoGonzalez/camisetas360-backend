package com.camisetas360.orders.messaging.listener;

import com.camisetas360.orders.messaging.event.CheckoutItemEvent;
import com.camisetas360.orders.messaging.event.CheckoutRequestedEvent;
import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckoutRequestedListenerTest {

    @Mock
    private OrderService service;
    @InjectMocks
    private CheckoutRequestedListener listener;

    // UT-ORD-012
    @Test
    void handleCheckoutRequested_shouldDelegateOnce_whenEventIsValid() {
        var event = event();
        when(service.createOrder(event)).thenReturn(new Order());

        listener.handleCheckoutRequested(event);

        verify(service, times(1)).createOrder(same(event));
        verifyNoMoreInteractions(service);
    }

    // UT-ORD-013
    @Test
    void handleCheckoutRequested_shouldPropagateFailure_whenOrderServiceFails() {
        var event = event();
        var failure = new DataAccessResourceFailureException("save failed");
        when(service.createOrder(event)).thenThrow(failure);

        assertThat(assertThrows(DataAccessResourceFailureException.class,
                () -> listener.handleCheckoutRequested(event))).isSameAs(failure);

        verify(service, times(1)).createOrder(event);
        verifyNoMoreInteractions(service);
    }

    private static CheckoutRequestedEvent event() {
        return new CheckoutRequestedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "buyer@example.test", List.of(new CheckoutItemEvent("SKU-A", 2, 19.5)),
                Instant.parse("2026-01-01T12:00:00Z"));
    }
}
