package com.camisetas360.analytics.model;

/** Datos no sensibles de un evento pago.fraude-detectado (nunca incluye el hash de la tarjeta). */
public record AlertaFraude(String ordenId, Double monto, String ultimos4, String motivo) {
}
