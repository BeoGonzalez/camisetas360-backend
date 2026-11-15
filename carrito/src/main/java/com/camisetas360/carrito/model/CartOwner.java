package com.camisetas360.carrito.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class CartOwner implements Serializable {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "object_id", nullable = false)
    private UUID objectId;

    protected CartOwner() {
    }

    public CartOwner(UUID tenantId, UUID objectId) {
        this.tenantId = tenantId;
        this.objectId = objectId;
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getObjectId() { return objectId; }

    @Override
    public boolean equals(Object other) {
        return other instanceof CartOwner owner
                && Objects.equals(tenantId, owner.tenantId)
                && Objects.equals(objectId, owner.objectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, objectId);
    }
}
