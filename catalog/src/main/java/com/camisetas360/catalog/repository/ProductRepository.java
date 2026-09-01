package com.camisetas360.catalog.repository;

import com.camisetas360.catalog.models.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio para la gestión de persistencia de la entidad Product.
 * Spring Data JPA proporciona la implementación transaccional en tiempo de ejecución.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
}