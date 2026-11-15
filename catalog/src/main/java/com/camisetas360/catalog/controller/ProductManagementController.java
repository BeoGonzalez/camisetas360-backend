package com.camisetas360.catalog.controller;

import com.camisetas360.catalog.dtos.ProductCreateRequest;
import com.camisetas360.catalog.dtos.ProductDTO;
import com.camisetas360.catalog.dtos.ProductUpdateRequest;
import com.camisetas360.catalog.models.ProductImage;
import com.camisetas360.catalog.service.ImageTooLargeException;
import com.camisetas360.catalog.service.ProductManagementService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Administración del catálogo. Escrituras: rol CATALOG_MANAGER o ADMIN (ver SecurityConfig).
 * La foto se lee con los mismos permisos que el resto del catálogo.
 */
@RestController
@RequestMapping("/api/v1/catalog/products")
public class ProductManagementController {

    private static final Pattern SKU = Pattern.compile(ProductCreateRequest.SKU_REGEX);

    private final ProductManagementService service;

    public ProductManagementController(ProductManagementService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProductDTO> create(@Valid @RequestBody ProductCreateRequest request) {
        ProductDTO created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/catalog/products/" + created.sku())).body(created);
    }

    @PutMapping(path = "/{sku}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProductDTO> update(@PathVariable String sku, @Valid @RequestBody ProductUpdateRequest request) {
        if (!SKU.matcher(sku).matches()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(service.update(sku, request));
    }

    @DeleteMapping("/{sku}")
    public ResponseEntity<Void> delete(@PathVariable String sku) {
        if (!SKU.matcher(sku).matches()) return ResponseEntity.badRequest().build();
        service.delete(sku);
        return ResponseEntity.noContent().build();
    }

    /** El front pide la foto con ?v={imageVersion}: cada versión es inmutable y se puede cachear. */
    @GetMapping("/{sku}/imagen")
    public ResponseEntity<byte[]> getImage(@PathVariable String sku) {
        if (!SKU.matcher(sku).matches()) return ResponseEntity.badRequest().build();
        ProductImage image = service.getImage(sku);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                .body(image.getContent());
    }

    /** Cuerpo = bytes de la imagen (Content-Type image/jpeg, image/png o image/webp), máx. 2 MB. */
    @PutMapping(path = "/{sku}/imagen",
            consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp"})
    public ResponseEntity<ProductDTO> putImage(@PathVariable String sku, InputStream body) throws IOException {
        if (!SKU.matcher(sku).matches()) return ResponseEntity.badRequest().build();
        // Se lee como máximo un byte de más: un cuerpo enorme no llega a memoria.
        byte[] content = body.readNBytes(ProductManagementService.MAX_IMAGE_BYTES + 1);
        if (content.length > ProductManagementService.MAX_IMAGE_BYTES) {
            throw new ImageTooLargeException(ProductManagementService.MAX_IMAGE_BYTES);
        }
        return ResponseEntity.ok(service.putImage(sku, content));
    }

    @DeleteMapping("/{sku}/imagen")
    public ResponseEntity<ProductDTO> deleteImage(@PathVariable String sku) {
        if (!SKU.matcher(sku).matches()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(service.deleteImage(sku));
    }
}
