package com.camisetas360.catalog.repository;

import com.camisetas360.catalog.models.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio para la gestión de persistencia de la entidad Product.
 * Spring Data JPA proporciona la implementación transaccional en tiempo de ejecución.
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** El SKU no tiene restriccion UNIQUE en el esquema: se toma el registro mas antiguo. */
    Optional<Product> findFirstBySkuOrderByIdAsc(String sku);

    /** Busqueda por lote (una sola consulta); con SKU duplicados el primero por id es el vigente. */
    List<Product> findBySkuInOrderByIdAsc(Collection<String> skus);
}
