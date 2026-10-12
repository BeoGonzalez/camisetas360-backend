package com.camisetas360.carrito.model;

import com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "checkout_requests")
public class CheckoutRequest {
    @Id private UUID id;
    @Column(name = "user_email", nullable = false) private String userEmail;
    @Column(name = "tenant_id") private java.util.UUID tenantId;
    @Column(name = "object_id") private java.util.UUID objectId;
    @Column(name = "total_amount", nullable = false) private Double totalAmount;
    @Column(nullable = false) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @OneToMany(mappedBy = "checkout", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CheckoutRequestItem> items = new ArrayList<>();

    protected CheckoutRequest() { }
    public CheckoutRequest(CheckoutRequestedEvent event, double total) {
        id = event.eventId();
        userEmail = event.userEmail();
        tenantId = event.tenantId();
        objectId = event.objectId();
        totalAmount = total;
        status = "PROCESSING";
        createdAt = event.occurredAt();
        event.items().forEach(item -> items.add(new CheckoutRequestItem(this, item.sku(), item.quantity(), item.unitPrice())));
    }
    public UUID getId() { return id; }
    public String getUserEmail() { return userEmail; }
    public Double getTotalAmount() { return totalAmount; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public List<CheckoutRequestItem> getItems() { return List.copyOf(items); }
    public java.util.UUID getTenantId() { return tenantId; }
    public java.util.UUID getObjectId() { return objectId; }
    public void setTenantId(java.util.UUID tenantId) { this.tenantId = tenantId; }
    public void setObjectId(java.util.UUID objectId) { this.objectId = objectId; }
}
