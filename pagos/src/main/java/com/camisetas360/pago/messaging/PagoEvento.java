package com.camisetas360.pago.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Esquema versionado de evento de pago para Kafka.
 * Contrato consumible por ms-analytics-recommendations y otros consumidores.
 * Nunca incluye datos sensibles (PAN completo, CVV o fechas de expiración).
 */
public record PagoEvento(
        UUID eventId,
        Instant timestamp,
        int version,
        String eventType,
        UUID ordenId,
        UUID pagoId,
        BigDecimal monto,
        String estado,
        String tarjetaHash,
        String ultimos4,
        String motivo
) {
    public static final int SCHEMA_VERSION = 1;

    public static PagoEvento crear(
            String eventType,
            UUID ordenId,
            UUID pagoId,
            BigDecimal monto,
            String estado,
            String tarjetaHash,
            String ultimos4,
            String motivo
    ) {
        return new PagoEvento(
                UUID.randomUUID(),
                Instant.now(),
                SCHEMA_VERSION,
                eventType,
                ordenId,
                pagoId,
                monto,
                estado,
                tarjetaHash,
                ultimos4,
                motivo
        );
    }
}
