package com.camisetas360.carrito.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** El catalogo no respondio a tiempo o fallo: no se puede validar precios, el cliente puede reintentar. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class CatalogoNoDisponibleException extends RuntimeException {

    public CatalogoNoDisponibleException(Throwable cause) {
        super("El catalogo no esta disponible para validar los productos", cause);
    }
}
