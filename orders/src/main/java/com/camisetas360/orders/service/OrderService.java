package com.camisetas360.orders.service;

import com.camisetas360.orders.messaging.OrderEventPublisher;
import com.camisetas360.orders.messaging.event.CheckoutRequestedEvent;
import com.camisetas360.orders.messaging.event.OrderCreatedEvent;
import com.camisetas360.orders.messaging.event.OrderItemEvent;
import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import com.camisetas360.orders.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;

    public OrderService(
            OrderRepository orderRepository,
            OrderEventPublisher orderEventPublisher
    ) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
    }

    public Order createOrder(CheckoutRequestedEvent event) {

        Order order = new Order();

        order.setUserEmail(event.userEmail());
        order.setStatus(OrderStatus.CREATED);
        order.setCreatedAt(Instant.now());

        List<OrderItem> items = event.items()
                .stream()
                .map(itemEvent -> {
                    OrderItem item = new OrderItem();

                    item.setSku(itemEvent.sku());
                    item.setQuantity(itemEvent.quantity());
                    item.setUnitPrice(itemEvent.unitPrice());

                    return item;
                })
                .toList();

        double total = items.stream()
                .mapToDouble(item ->
                        item.getUnitPrice() * item.getQuantity()
                )
                .sum();

        order.setItems(items);
        order.setTotalAmount(total);

        Order savedOrder = orderRepository.save(order);

        List<OrderItemEvent> orderItems = savedOrder.getItems()
                .stream()
                .map(item -> new OrderItemEvent(
                        item.getSku(),
                        item.getQuantity(),
                        item.getUnitPrice()
                ))
                .toList();

        OrderCreatedEvent orderCreatedEvent = new OrderCreatedEvent(
                UUID.randomUUID(),
                savedOrder.getId(),
                savedOrder.getUserEmail(),
                savedOrder.getTotalAmount(),
                orderItems,
                Instant.now()
        );

        orderEventPublisher.publishOrderCreated(orderCreatedEvent);

        return savedOrder;
    }
}