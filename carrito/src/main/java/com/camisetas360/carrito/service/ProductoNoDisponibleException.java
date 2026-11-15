package com.camisetas360.carrito.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Collection;

/** Algun SKU pedido no existe en el catalogo: la solicitud es valida pero no procesable. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ProductoNoDisponibleException extends RuntimeException {

    public ProductoNoDisponibleException(Collection<String> skus) {
        super("Productos no disponibles en el catalogo: " + String.join(", ", skus));
    }
}
