package com.camisetas360.carrito.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class CartItemNotFoundException extends RuntimeException {

    public CartItemNotFoundException(String sku) {
        super("El producto " + sku + " no esta en el carrito");
    }
}
