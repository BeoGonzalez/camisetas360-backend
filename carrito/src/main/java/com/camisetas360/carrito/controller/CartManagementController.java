package com.camisetas360.carrito.controller;

import com.camisetas360.carrito.dtos.AddCartItemRequest;
import com.camisetas360.carrito.dtos.CartDTO;
import com.camisetas360.carrito.dtos.ShippingRequest;
import com.camisetas360.carrito.security.EntraIdentity;
import com.camisetas360.carrito.service.CartManagementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/** Carrito del usuario autenticado: items y metodo de envio. */
@RestController
@RequestMapping("/api/v1/carrito")
public class CartManagementController {

    private static final Pattern SKU = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final CartManagementService service;

    public CartManagementController(CartManagementService service) {
        this.service = service;
    }

    @GetMapping
    public CartDTO obtener(@AuthenticationPrincipal Jwt jwt) {
        return service.obtener(EntraIdentity.from(jwt));
    }

    @PostMapping("/items")
    public CartDTO agregarItem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddCartItemRequest request) {
        return service.agregarItem(EntraIdentity.from(jwt), request);
    }

    @DeleteMapping("/items/{sku}")
    public ResponseEntity<CartDTO> eliminarItem(@AuthenticationPrincipal Jwt jwt, @PathVariable String sku) {
        if (!SKU.matcher(sku).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.eliminarItem(EntraIdentity.from(jwt), sku));
    }

    @PutMapping("/envio")
    public CartDTO actualizarEnvio(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ShippingRequest request) {
        return service.actualizarEnvio(EntraIdentity.from(jwt), request);
    }
}
