package com.camisetas360.pago.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alerta_fraude", schema = "pago")
public class AlertaFraude {

    @Id
    private UUID id;

    @Column(name = "orden_id", nullable = false)
    private UUID ordenId;

    @Column(name = "pago_id")
    private UUID pagoId;

    @Column(name = "tarjeta_hash", nullable = false, length = 64)
    private String tarjetaHash;

    @Column(name = "tarjeta_ultimos4", nullable = false, length = 4)
    private String tarjetaUltimos4;

    @Column(name = "intentos_detectados", nullable = false)
    private int intentosDetectados;

    @Column(name = "ventana_segundos", nullable = false)
    private int ventanaSegundos;

    @Column(nullable = false, length = 255)
    private String motivo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AlertaFraude() {}

    public AlertaFraude(UUID ordenId, UUID pagoId, String tarjetaHash, String tarjetaUltimos4,
                        int intentosDetectados, int ventanaSegundos, String motivo) {
        this.id = UUID.randomUUID();
        this.ordenId = ordenId;
        this.pagoId = pagoId;
        this.tarjetaHash = tarjetaHash;
        this.tarjetaUltimos4 = tarjetaUltimos4;
        this.intentosDetectados = intentosDetectados;
        this.ventanaSegundos = ventanaSegundos;
        this.motivo = motivo;
        this.createdAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOrdenId() {
        return ordenId;
    }

    public void setOrdenId(UUID ordenId) {
        this.ordenId = ordenId;
    }

    public UUID getPagoId() {
        return pagoId;
    }

    public void setPagoId(UUID pagoId) {
        this.pagoId = pagoId;
    }

    public String getTarjetaHash() {
        return tarjetaHash;
    }

    public void setTarjetaHash(String tarjetaHash) {
        this.tarjetaHash = tarjetaHash;
    }

    public String getTarjetaUltimos4() {
        return tarjetaUltimos4;
    }

    public void setTarjetaUltimos4(String tarjetaUltimos4) {
        this.tarjetaUltimos4 = tarjetaUltimos4;
    }

    public int getIntentosDetectados() {
        return intentosDetectados;
    }

    public void setIntentosDetectados(int intentosDetectados) {
        this.intentosDetectados = intentosDetectados;
    }

    public int getVentanaSegundos() {
        return ventanaSegundos;
    }

    public void setVentanaSegundos(int ventanaSegundos) {
        this.ventanaSegundos = ventanaSegundos;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
