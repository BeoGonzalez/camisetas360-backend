package com.camisetas360.catalog.service;

import com.camisetas360.catalog.dtos.ProductDTO;
import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio que encapsula la lógica de negocio del catálogo de productos.
 */
@Service
public class CatalogService {

    private final ProductRepository productRepository;

    /**
     * Inyección de dependencias mediante constructor para garantizar inmutabilidad.
     * @param productRepository Repositorio de productos.
     */
    public CatalogService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Recupera todos los productos y los mapea a objetos de transferencia (DTO).
     * @return Lista de ProductDTO listos para la capa web.
     */
    public List<ProductDTO> getAllProducts() {
        return productRepository.findAll()
                .stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Método auxiliar privado para transformar la entidad en un DTO.
     */
    private ProductDTO mapToDTO(Product product) {
        return new ProductDTO(
                product.getSku(),
                product.getName(),
                product.getCategory(),
                product.getPrice(),
                product.getStock(),
                product.getDescription()
        );
    }
}