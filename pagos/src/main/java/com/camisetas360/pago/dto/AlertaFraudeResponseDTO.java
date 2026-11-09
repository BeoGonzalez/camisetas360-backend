package com.camisetas360.pago.dto;

import com.camisetas360.pago.model.AlertaFraude;

import java.time.Instant;
import java.util.UUID;

public record AlertaFraudeResponseDTO(
        UUID id,
        UUID ordenId,
        UUID pagoId,
        String tarjetaHash,
        String tarjetaUltimos4,
        int intentosDetectados,
        int ventanaSegundos,
        String motivo,
        Instant createdAt
) {
    public static AlertaFraudeResponseDTO fromEntity(AlertaFraude alerta) {
        return new AlertaFraudeResponseDTO(
                alerta.getId(),
                alerta.getOrdenId(),
                alerta.getPagoId(),
                alerta.getTarjetaHash(),
                alerta.getTarjetaUltimos4(),
                alerta.getIntentosDetectados(),
                alerta.getVentanaSegundos(),
                alerta.getMotivo(),
                alerta.getCreatedAt()
        );
    }
}
