package com.camisetas360.carrito.service;

import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.dtos.OrderResponseDTO;
import com.camisetas360.carrito.model.Order;
import com.camisetas360.carrito.model.OrderItem;
import com.camisetas360.carrito.repository.OrderRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CartService {

    private final OrderRepository orderRepository;

    public CartService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderResponseDTO createOrder(OrderRequestDTO request) {
        // Extraemos el JWT validado de la sesión actual
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        // Leemos el claim del correo o identificador configurado en Azure
        String userEmail = jwt.getClaimAsString("preferred_username");

        Order order = new Order();
        order.setUserEmail(userEmail != null ? userEmail : "usuario_desconocido");
        order.setStatus("COMPLETED");

        double total = 0.0;
        List<OrderItem> items = request.items().stream().map(dto -> {
            OrderItem item = new OrderItem();
            item.setSku(dto.sku());
            item.setQuantity(dto.quantity());
            item.setUnitPrice(dto.unitPrice());
            return item;
        }).collect(Collectors.toList());

        for (OrderItem item : items) {
            total += (item.getUnitPrice() * item.getQuantity());
        }

        order.setItems(items);
        order.setTotalAmount(total);

        Order savedOrder = orderRepository.save(order);

        return new OrderResponseDTO(
                savedOrder.getId(),
                savedOrder.getUserEmail(),
                savedOrder.getTotalAmount(),
                savedOrder.getStatus()
        );
    }
}