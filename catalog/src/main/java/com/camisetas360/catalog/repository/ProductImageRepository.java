package com.camisetas360.catalog.repository;

import com.camisetas360.catalog.models.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Fotos de productos; la clave es el id del producto. */
@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
}
