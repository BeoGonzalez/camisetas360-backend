package com.camisetas360.catalog.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Foto de un producto. Va en su propia tabla (PK = id del producto) para que listar el
 * catalogo no cargue los bytes de todas las imagenes.
 */
@Entity
@Table(name = "product_images")
public class ProductImage {
    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "content_type", nullable = false, length = 30)
    private String contentType;

    @Column(name = "content", nullable = false)
    private byte[] content;

    protected ProductImage() {}

    public ProductImage(Long productId, String contentType, byte[] content) {
        this.productId = productId;
        this.contentType = contentType;
        this.content = content;
    }

    public Long getProductId() {
        return productId;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getContent() {
        return content;
    }
}
