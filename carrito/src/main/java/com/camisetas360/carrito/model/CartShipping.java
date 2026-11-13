package com.camisetas360.carrito.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "cart_shipping")
public class CartShipping {

    @EmbeddedId
    private CartOwner owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShippingMethod method;

    @Column(nullable = false, length = 100)
    private String region;

    @Column(nullable = false, length = 100)
    private String commune;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CartShipping() {
    }

    public CartShipping(CartOwner owner) {
        this.owner = owner;
    }

    public void update(ShippingMethod method, String region, String commune, Instant now) {
        this.method = method;
        this.region = region;
        this.commune = commune;
        this.updatedAt = now;
    }

    public CartOwner getOwner() { return owner; }
    public ShippingMethod getMethod() { return method; }
    public String getRegion() { return region; }
    public String getCommune() { return commune; }
    public Instant getUpdatedAt() { return updatedAt; }
}
