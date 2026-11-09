package com.camisetas360.pago.messaging;

public final class PagoTopicos {

    private PagoTopicos() {}

    public static final String INTENTO_REGISTRADO = "pago.intento-registrado";
    public static final String AUTORIZADO = "pago.autorizado";
    public static final String RECHAZADO = "pago.rechazado";
    public static final String FRAUDE_DETECTADO = "pago.fraude-detectado";
}
