package com.camisetas360.recommendation.model;

/** Tipo de interaccion y su peso en el puntaje (guia: ver = 1, carrito = 2, compra = 3). */
public enum Accion {
    VISTA(1),
    CARRITO(2),
    COMPRA(3);

    private final int peso;

    Accion(int peso) {
        this.peso = peso;
    }

    public int peso() {
        return peso;
    }
}
