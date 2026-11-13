package com.camisetas360.catalog.service;

import com.camisetas360.catalog.dtos.ProductDTO;
import com.camisetas360.catalog.messaging.InteraccionEventPublisher;
import com.camisetas360.catalog.messaging.KafkaTopics;
import com.camisetas360.catalog.messaging.event.InteraccionEvento;
import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Servicio que encapsula la lógica de negocio del catálogo de productos.
 */
@Service
public class CatalogService {

    private final ProductRepository productRepository;
    private final InteraccionEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public CatalogService(ProductRepository productRepository, InteraccionEventPublisher eventPublisher) {
        this(productRepository, eventPublisher, Clock.systemUTC());
    }

    CatalogService(ProductRepository productRepository, InteraccionEventPublisher eventPublisher, Clock clock) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
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
     * Productos de los SKU indicados (los inexistentes se omiten). Lo usan otros servicios
     * (carrito) para validar precios: no es una visita, por eso no publica PRODUCTO_VISTO.
     */
    public List<ProductDTO> getProductsBySkus(List<String> skus) {
        List<String> unicos = skus.stream().distinct().toList();
        Map<String, Product> vigentes = new LinkedHashMap<>();
        productRepository.findBySkuInOrderByIdAsc(unicos).forEach(p -> vigentes.putIfAbsent(p.getSku(), p));
        return unicos.stream().map(vigentes::get).filter(Objects::nonNull).map(this::mapToDTO).toList();
    }

    /**
     * Devuelve el detalle de un producto y publica el evento PRODUCTO_VISTO
     * para el pipeline de analitica y recomendaciones.
     * @throws ProductNotFoundException si el SKU no existe (no se publica evento).
     */
    public ProductDTO getProductDetail(String sku, String usuarioId) {
        Product product = productRepository.findFirstBySkuOrderByIdAsc(sku)
                .orElseThrow(() -> new ProductNotFoundException(sku));

        eventPublisher.publicar(KafkaTopics.PRODUCTO_VISTO, new InteraccionEvento(
                UUID.randomUUID(),
                "PRODUCTO_VISTO",
                InteraccionEvento.VERSION,
                Instant.now(clock).toString(),
                "catalog",
                usuarioId,
                new InteraccionEvento.ProductoSnapshot(
                        product.getSku(), product.getName(), product.getCategory(), product.getPrice()),
                null,
                null));

        return mapToDTO(product);
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
