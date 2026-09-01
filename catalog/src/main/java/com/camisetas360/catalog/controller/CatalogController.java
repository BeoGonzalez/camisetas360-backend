package com.camisetas360.catalog.controller;

import com.camisetas360.catalog.dtos.ProductDTO;
import com.camisetas360.catalog.service.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador REST para exponer las operaciones del catálogo.
 * Implementa versionado semántico en la ruta base (v1).
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Obtiene el listado completo de camisetas disponibles.
     * @return Respuesta HTTP 200 con la lista de productos.
     */
    @GetMapping("/products")
    public ResponseEntity<List<ProductDTO>> getProducts() {
        List<ProductDTO> products = catalogService.getAllProducts();
        return ResponseEntity.ok(products);
    }
}