package com.camisetas360.carrito.model;

import jakarta.persistence.*;

@Entity
@Table(name = "checkout_request_items")
public class CheckoutRequestItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "checkout_id", nullable = false)
    private CheckoutRequest checkout;
    @Column(nullable = false) private String sku;
    @Column(nullable = false) private Integer quantity;
    @Column(name = "unit_price", nullable = false) private Double unitPrice;

    protected CheckoutRequestItem() { }
    public CheckoutRequestItem(CheckoutRequest checkout, String sku, int quantity, double unitPrice) {
        this.checkout = checkout;
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }
    public String getSku() { return sku; }
    public Integer getQuantity() { return quantity; }
    public Double getUnitPrice() { return unitPrice; }
}
