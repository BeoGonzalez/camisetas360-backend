package com.camisetas360.carrito.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class CartItemKey implements Serializable {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "object_id", nullable = false)
    private UUID objectId;

    @Column(nullable = false, length = 64)
    private String sku;

    protected CartItemKey() {
    }

    public CartItemKey(UUID tenantId, UUID objectId, String sku) {
        this.tenantId = tenantId;
        this.objectId = objectId;
        this.sku = sku;
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getObjectId() { return objectId; }
    public String getSku() { return sku; }

    @Override
    public boolean equals(Object other) {
        return other instanceof CartItemKey key
                && Objects.equals(tenantId, key.tenantId)
                && Objects.equals(objectId, key.objectId)
                && Objects.equals(sku, key.sku);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, objectId, sku);
    }
}
