package com.camisetas360.carrito.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class CartLimitExceededException extends RuntimeException {

    public CartLimitExceededException(String sku, int max) {
        super("No se pueden tener mas de " + max + " unidades de " + sku + " en el carrito");
    }
}
