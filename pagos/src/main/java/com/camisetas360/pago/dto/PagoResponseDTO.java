package com.camisetas360.pago.dto;

import com.camisetas360.pago.model.EstadoPago;
import com.camisetas360.pago.model.Pago;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PagoResponseDTO(
        UUID id,
        UUID ordenId,
        BigDecimal monto,
        String moneda,
        EstadoPago estado,
        String tokenTransaccion,
        String codigoAutorizacion,
        String tarjetaUltimos4,
        String tarjetaMarca,
        String mensajeRespuesta,
        Instant createdAt,
        Instant updatedAt
) {
    public static PagoResponseDTO fromEntity(Pago pago) {
        return new PagoResponseDTO(
                pago.getId(),
                pago.getOrdenId(),
                pago.getMonto(),
                pago.getMoneda(),
                pago.getEstado(),
                pago.getTokenTransaccion(),
                pago.getCodigoAutorizacion(),
                pago.getTarjetaUltimos4(),
                pago.getTarjetaMarca(),
                pago.getMensajeRespuesta(),
                pago.getCreatedAt(),
                pago.getUpdatedAt()
        );
    }
}
