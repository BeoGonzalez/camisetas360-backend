package com.camisetas360.orders.controller;

import com.camisetas360.orders.dto.OrderResponseDTO;
import com.camisetas360.orders.service.OrderService;
import com.camisetas360.orders.security.EntraIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public ResponseEntity<List<OrderResponseDTO>> getMyOrders(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(orders.findByOwner(EntraIdentity.from(jwt)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponseDTO> getOrderById(@PathVariable Long orderId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(orders.findById(orderId, EntraIdentity.from(jwt)));
    }
}
