package com.camisetas360.carrito.controller;

import com.camisetas360.carrito.dtos.CheckoutResponseDTO;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.service.CartService;
import jakarta.validation.Valid;
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
    public ResponseEntity<CheckoutResponseDTO> checkout(
            @Valid @RequestBody OrderRequestDTO request
    ) {
        CheckoutResponseDTO response = cartService.createOrder(request);

        return ResponseEntity.accepted().body(response);
    }
}