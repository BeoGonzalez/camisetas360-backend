package com.camisetas360.carrito.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "cart_items")
public class CartItem {

    public static final int MAX_QUANTITY = 99;

    @EmbeddedId
    private CartItemKey key;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "category")
    private String category;

    @Column(name = "unit_price", nullable = false)
    private Double unitPrice;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CartItem() {
    }

    public CartItem(CartItemKey key, String productName, String category, double unitPrice, int quantity, Instant now) {
        this.key = key;
        this.productName = productName;
        this.category = category;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.updatedAt = now;
    }

    /** Suma unidades y actualiza la foto del producto (nombre, categoria y precio vigentes). */
    public void addUnits(String productName, String category, double unitPrice, int units, Instant now) {
        this.productName = productName;
        this.category = category;
        this.unitPrice = unitPrice;
        this.quantity = this.quantity + units;
        this.updatedAt = now;
    }

    public CartItemKey getKey() { return key; }
    public String getSku() { return key.getSku(); }
    public String getProductName() { return productName; }
    public String getCategory() { return category; }
    public Double getUnitPrice() { return unitPrice; }
    public Integer getQuantity() { return quantity; }
    public Instant getUpdatedAt() { return updatedAt; }
}
