package com.camisetas360.orders.service;

import com.camisetas360.orders.dto.OrderItemResponseDTO;
import com.camisetas360.orders.dto.OrderResponseDTO;
import com.camisetas360.orders.exception.OrderNotFoundException;
import com.camisetas360.orders.messaging.OrderEventPublisher;
import com.camisetas360.orders.messaging.event.CheckoutItemEvent;
import com.camisetas360.orders.messaging.event.CheckoutRequestedEvent;
import com.camisetas360.orders.messaging.event.OrderCreatedEvent;
import com.camisetas360.orders.messaging.event.OrderItemEvent;
import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import com.camisetas360.orders.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");
    private static final String EMAIL = "buyer@example.test";

    @Mock
    private OrderRepository repository;

    @Mock
    private OrderEventPublisher publisher;

    @InjectMocks
    private OrderService service;

    // UT-ORD-001
    @Test
    void createOrder_shouldPersistAggregateAndPublish_whenEventIsValid() {
        when(repository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 42L);
            return order;
        });

        var result = service.createOrder(checkout());

        var orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(repository, times(1)).save(orderCaptor.capture());
        var order = orderCaptor.getValue();
        assertThat(result).isSameAs(order);
        assertThat(order.getUserEmail()).isEqualTo(EMAIL);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getTotalAmount()).isEqualTo(69.0);
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getItems().get(0)).satisfies(item -> {
            assertThat(item.getSku()).isEqualTo("SKU-A");
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getUnitPrice()).isEqualTo(19.5);
            assertThat(item.getOrder()).isSameAs(order);
        });
        assertThat(order.getItems().get(1)).satisfies(item -> {
            assertThat(item.getSku()).isEqualTo("SKU-B");
            assertThat(item.getQuantity()).isEqualTo(3);
            assertThat(item.getUnitPrice()).isEqualTo(10.0);
            assertThat(item.getOrder()).isSameAs(order);
        });
        var eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(publisher, times(1)).publishOrderCreated(eventCaptor.capture());
        var published = eventCaptor.getValue();
        assertThat(published.orderId()).isEqualTo(42L);
        assertThat(published.userEmail()).isEqualTo(EMAIL);
        assertThat(published.totalAmount()).isEqualTo(69.0);
        assertThat(published.items()).containsExactly(
                new OrderItemEvent("SKU-A", 2, 19.5),
                new OrderItemEvent("SKU-B", 3, 10.0));
        assertThat(published.eventId()).isNotNull();
        assertThat(published.occurredAt()).isNotNull();
        verifyNoMoreInteractions(repository, publisher);
    }

    // UT-ORD-002
    @ParameterizedTest
    @MethodSource("validTotals")
    void createOrder_shouldCalculateTotal_whenEventHasDifferentQuantities(
            List<CheckoutItemEvent> items, double expectedTotal) {
        when(repository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var event = new CheckoutRequestedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                EMAIL, items, CREATED_AT);

        var result = service.createOrder(event);

        var captor = ArgumentCaptor.forClass(Order.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTotalAmount()).isEqualTo(expectedTotal);
        assertThat(result.getTotalAmount()).isEqualTo(expectedTotal);
        verify(publisher).publishOrderCreated(any());
        verifyNoMoreInteractions(repository, publisher);
    }

    static Stream<Arguments> validTotals() {
        return Stream.of(
                Arguments.of(List.of(new CheckoutItemEvent("A", 2, 19.5),
                        new CheckoutItemEvent("B", 3, 10.0)), 69.0),
                Arguments.of(List.of(new CheckoutItemEvent("A", 1, 19.5)), 19.5),
                Arguments.of(List.of(new CheckoutItemEvent("A", 1, 0.01)), 0.01));
    }

    // UT-ORD-003
    @Test
    void createOrder_shouldUseSavedOrderData_whenRepositoryReturnsAnotherInstance() {
        var saved = storedOrder(91L);
        saved.setUserEmail("saved@example.test");
        saved.setTotalAmount(50.0);
        saved.setItems(List.of(item("SAVED-SKU", 4, 12.5)));
        when(repository.save(any(Order.class))).thenReturn(saved);

        var result = service.createOrder(checkout());

        assertThat(result).isSameAs(saved);
        var captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(publisher).publishOrderCreated(captor.capture());
        var event = captor.getValue();
        assertThat(event.orderId()).isEqualTo(91L);
        assertThat(event.userEmail()).isEqualTo("saved@example.test");
        assertThat(event.totalAmount()).isEqualTo(50.0);
        assertThat(event.items()).containsExactly(new OrderItemEvent("SAVED-SKU", 4, 12.5));
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
        verify(repository).save(any(Order.class));
        verifyNoMoreInteractions(repository, publisher);
    }

    // UT-ORD-004
    @Test
    void createOrder_shouldNotPublish_whenRepositorySaveFails() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(repository.save(any(Order.class))).thenThrow(failure);

        assertThat(assertThrows(DataAccessResourceFailureException.class,
                () -> service.createOrder(checkout()))).isSameAs(failure);

        verify(repository).save(any(Order.class));
        verifyNoMoreInteractions(repository);
        verify(publisher, never()).publishOrderCreated(any());
    }

    // UT-ORD-005
    @Test
    void createOrder_shouldPropagateFailure_whenPublisherFailsAfterSave() {
        when(repository.save(any(Order.class))).thenReturn(storedOrder(42L));
        var failure = new AmqpException("broker unavailable");
        doThrow(failure).when(publisher).publishOrderCreated(any());

        assertThat(assertThrows(AmqpException.class,
                () -> service.createOrder(checkout()))).isSameAs(failure);

        verify(repository, times(1)).save(any(Order.class));
        verify(publisher, times(1)).publishOrderCreated(any());
        verifyNoMoreInteractions(repository, publisher);
    }

    // UT-ORD-006
    @ParameterizedTest
    @ValueSource(strings = {EMAIL, "BUYER@EXAMPLE.TEST"})
    void findById_shouldReturnOwnOrder_whenEmailMatchesIgnoringCase(String requestingEmail) {
        when(repository.findById(42L)).thenReturn(Optional.of(storedOrder(42L)));

        assertThat(service.findById(42L, requestingEmail)).isEqualTo(expectedResponse(42L));

        verify(repository).findById(42L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(publisher);
    }

    // UT-ORD-007
    @Test
    void findById_shouldThrowOrderNotFound_whenOrderBelongsToAnotherUser() {
        when(repository.findById(42L)).thenReturn(Optional.of(storedOrder(42L)));

        assertThat(assertThrows(OrderNotFoundException.class,
                () -> service.findById(42L, "another@example.test")))
                .hasMessage("Order not found: 42");

        verify(repository).findById(42L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(publisher);
    }

    // UT-ORD-007
    @Test
    void findById_shouldThrowOrderNotFound_whenOrderDoesNotExist() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        assertThat(assertThrows(OrderNotFoundException.class,
                () -> service.findById(42L, EMAIL))).hasMessage("Order not found: 42");

        verify(repository).findById(42L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(publisher);
    }

    // UT-ORD-008
    @Test
    void findByUserEmail_shouldMapOrdersInRepositoryOrder_whenOrdersExist() {
        when(repository.findByUserEmailOrderByCreatedAtDesc(EMAIL))
                .thenReturn(List.of(storedOrder(43L), storedOrder(42L)));

        assertThat(service.findByUserEmail(EMAIL))
                .containsExactly(expectedResponse(43L), expectedResponse(42L));

        verify(repository).findByUserEmailOrderByCreatedAtDesc(EMAIL);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(publisher);
    }

    // UT-ORD-008
    @Test
    void findByUserEmail_shouldReturnEmptyList_whenUserHasNoOrders() {
        when(repository.findByUserEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(List.of());

        assertThat(service.findByUserEmail(EMAIL)).isEmpty();

        verify(repository).findByUserEmailOrderByCreatedAtDesc(EMAIL);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(publisher);
    }

    // UT-ORD-009
    @Test
    void findById_shouldPropagateFailure_whenRepositoryFails() {
        var failure = new DataAccessResourceFailureException("read failed");
        when(repository.findById(42L)).thenThrow(failure);

        assertThat(assertThrows(DataAccessResourceFailureException.class,
                () -> service.findById(42L, EMAIL))).isSameAs(failure);

        verify(repository).findById(42L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(publisher);
    }

    // UT-ORD-009
    @Test
    void findByUserEmail_shouldPropagateFailure_whenRepositoryFails() {
        var failure = new DataAccessResourceFailureException("read failed");
        when(repository.findByUserEmailOrderByCreatedAtDesc(EMAIL)).thenThrow(failure);

        assertThat(assertThrows(DataAccessResourceFailureException.class,
                () -> service.findByUserEmail(EMAIL))).isSameAs(failure);

        verify(repository).findByUserEmailOrderByCreatedAtDesc(EMAIL);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(publisher);
    }

    private static CheckoutRequestedEvent checkout() {
        return new CheckoutRequestedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                EMAIL, List.of(new CheckoutItemEvent("SKU-A", 2, 19.5),
                new CheckoutItemEvent("SKU-B", 3, 10.0)), CREATED_AT);
    }

    private static Order storedOrder(Long id) {
        var order = new Order();
        // IDENTITY is assigned by JPA in production; this fixture stays a real entity.
        ReflectionTestUtils.setField(order, "id", id);
        order.setUserEmail(EMAIL);
        order.setStatus(OrderStatus.CREATED);
        order.setCreatedAt(CREATED_AT);
        order.setTotalAmount(69.0);
        order.setItems(List.of(item("SKU-A", 2, 19.5), item("SKU-B", 3, 10.0)));
        return order;
    }

    private static OrderItem item(String sku, int quantity, double price) {
        var item = new OrderItem();
        item.setSku(sku);
        item.setQuantity(quantity);
        item.setUnitPrice(price);
        return item;
    }

    private static OrderResponseDTO expectedResponse(Long id) {
        return new OrderResponseDTO(id, EMAIL, 69.0, OrderStatus.CREATED, CREATED_AT,
                List.of(new OrderItemResponseDTO("SKU-A", 2, 19.5),
                        new OrderItemResponseDTO("SKU-B", 3, 10.0)));
    }
}
