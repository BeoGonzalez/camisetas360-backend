package com.camisetas360.orders.controller;

import com.camisetas360.orders.dto.OrderResponseDTO;
import com.camisetas360.orders.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<List<OrderResponseDTO>> getMyOrders(
            @AuthenticationPrincipal Jwt jwt
    ) {

        String userEmail =
                jwt.getClaimAsString("preferred_username");

        List<OrderResponseDTO> orders =
                orderService.findByUserEmail(userEmail);

        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponseDTO> getOrderById(
            @PathVariable Long orderId,
            @AuthenticationPrincipal Jwt jwt
    ) {

        String userEmail =
                jwt.getClaimAsString("preferred_username");

        OrderResponseDTO order =
                orderService.findById(
                        orderId,
                        userEmail
                );

        return ResponseEntity.ok(order);
    }
}