package com.camisetas360.catalog.controller;

import com.camisetas360.catalog.dtos.ProductDTO;
import com.camisetas360.catalog.messaging.UsuarioAnalitico;
import com.camisetas360.catalog.service.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Controlador REST para exponer las operaciones del catálogo.
 * Implementa versionado semántico en la ruta base (v1).
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private static final Pattern SKU = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_SKUS_FILTRO = 50;

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Obtiene el listado completo de camisetas disponibles.
     * @return Respuesta HTTP 200 con la lista de productos.
     */
    @GetMapping("/products")
    public ResponseEntity<List<ProductDTO>> getProducts(
            @RequestParam(name = "sku", required = false) List<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return ResponseEntity.ok(catalogService.getAllProducts());
        }
        if (skus.size() > MAX_SKUS_FILTRO || !skus.stream().allMatch(s -> SKU.matcher(s).matches())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(catalogService.getProductsBySkus(skus));
    }

    /**
     * Detalle de un producto. Registra la visita como evento de analitica.
     * @return 200 con el producto, 400 si el SKU es invalido o 404 si no existe.
     */
    @GetMapping("/products/{sku}")
    public ResponseEntity<ProductDTO> getProduct(@PathVariable String sku, @AuthenticationPrincipal Jwt jwt) {
        if (!SKU.matcher(sku).matches()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(catalogService.getProductDetail(sku, UsuarioAnalitico.desde(jwt)));
    }
}
