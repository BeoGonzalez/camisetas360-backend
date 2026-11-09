package com.camisetas360.pago.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pago", schema = "pago")
public class Pago {

    @Id
    private UUID id;

    @Column(name = "orden_id", nullable = false)
    private UUID ordenId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false, length = 3)
    private String moneda = "CLP";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EstadoPago estado;

    @Column(name = "token_transaccion", unique = true)
    private String tokenTransaccion;

    @Column(name = "codigo_autorizacion", length = 50)
    private String codigoAutorizacion;

    @Column(name = "tarjeta_hash", nullable = false, length = 64)
    private String tarjetaHash;

    @Column(name = "tarjeta_ultimos4", nullable = false, length = 4)
    private String tarjetaUltimos4;

    @Column(name = "tarjeta_marca", nullable = false, length = 30)
    private String tarjetaMarca;

    @Column(name = "mensaje_respuesta")
    private String mensajeRespuesta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Pago() {}

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
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

    public BigDecimal getMonto() {
        return monto;
    }

    public void setMonto(BigDecimal monto) {
        this.monto = monto;
    }

    public String getMoneda() {
        return moneda;
    }

    public void setMoneda(String moneda) {
        this.moneda = moneda;
    }

    public EstadoPago getEstado() {
        return estado;
    }

    public void setEstado(EstadoPago estado) {
        this.estado = estado;
    }

    public String getTokenTransaccion() {
        return tokenTransaccion;
    }

    public void setTokenTransaccion(String tokenTransaccion) {
        this.tokenTransaccion = tokenTransaccion;
    }

    public String getCodigoAutorizacion() {
        return codigoAutorizacion;
    }

    public void setCodigoAutorizacion(String codigoAutorizacion) {
        this.codigoAutorizacion = codigoAutorizacion;
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

    public String getTarjetaMarca() {
        return tarjetaMarca;
    }

    public void setTarjetaMarca(String tarjetaMarca) {
        this.tarjetaMarca = tarjetaMarca;
    }

    public String getMensajeRespuesta() {
        return mensajeRespuesta;
    }

    public void setMensajeRespuesta(String mensajeRespuesta) {
        this.mensajeRespuesta = mensajeRespuesta;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
