package com.camisetas360.carrito.model;

/** Metodos de envio disponibles y su costo fijo. */
public enum ShippingMethod {
    STANDARD(3990.0),
    EXPRESS(6990.0),
    RETIRO_TIENDA(0.0);

    private final double cost;

    ShippingMethod(double cost) {
        this.cost = cost;
    }

    public double cost() {
        return cost;
    }
}
