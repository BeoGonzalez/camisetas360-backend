package com.camisetas360.catalog.models;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Entidad que representa un producto (camiseta) en la base de datos.
 */
@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sku;
    private String name;
    private String category;
    private Double price;
    private Integer stock;
    private String description;
    private String league;
    private String team;
    @Enumerated(EnumType.STRING)
    @Column(name = "kit_type", length = 20)
    private KitType kitType;
    /** Ultima vez que se cambio la foto (version para el front); null = sin foto. */
    @Column(name = "image_updated_at")
    private Instant imageUpdatedAt;


    public Product() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLeague() {
        return league;
    }

    public void setLeague(String league) {
        this.league = league;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public KitType getKitType() {
        return kitType;
    }

    public void setKitType(KitType kitType) {
        this.kitType = kitType;
    }

    public Instant getImageUpdatedAt() {
        return imageUpdatedAt;
    }

    public void setImageUpdatedAt(Instant imageUpdatedAt) {
        this.imageUpdatedAt = imageUpdatedAt;
    }
}
