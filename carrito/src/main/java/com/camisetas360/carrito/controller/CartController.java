package com.camisetas360.carrito.controllers;

import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.dtos.OrderResponseDTO;
import com.camisetas360.carrito.service.CartService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/carrito")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @PostMapping("/checkout")
    public ResponseEntity<OrderResponseDTO> checkout(@RequestBody OrderRequestDTO request) {
        OrderResponseDTO response = cartService.createOrder(request);
        return ResponseEntity.ok(response);
    }
}