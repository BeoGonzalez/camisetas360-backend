package com.camisetas360.catalog.service;

import com.camisetas360.catalog.dtos.ProductCreateRequest;
import com.camisetas360.catalog.dtos.ProductDTO;
import com.camisetas360.catalog.dtos.ProductFields;
import com.camisetas360.catalog.dtos.ProductUpdateRequest;
import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.models.ProductImage;
import com.camisetas360.catalog.repository.ProductImageRepository;
import com.camisetas360.catalog.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * Gestión del catálogo (rol CATALOG_MANAGER o ADMIN): alta, edición, baja y fotos de las camisetas.
 * Cada camiseta (equipo + tipo) es un producto con su propio SKU, precio, stock y foto.
 */
@Service
@Transactional
public class ProductManagementService {

    /** Tamaño máximo de una foto. */
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final ProductRepository productRepository;
    private final ProductImageRepository imageRepository;
    private final Clock clock;

    @Autowired
    public ProductManagementService(ProductRepository productRepository, ProductImageRepository imageRepository) {
        this(productRepository, imageRepository, Clock.systemUTC());
    }

    ProductManagementService(ProductRepository productRepository, ProductImageRepository imageRepository, Clock clock) {
        this.productRepository = productRepository;
        this.imageRepository = imageRepository;
        this.clock = clock;
    }

    /** @throws ProductAlreadyExistsException si el SKU ya está en uso. */
    public ProductDTO create(ProductCreateRequest request) {
        String sku = request.sku().strip();
        if (productRepository.findFirstBySkuOrderByIdAsc(sku).isPresent()) {
            throw new ProductAlreadyExistsException(sku);
        }
        Product product = new Product();
        product.setSku(sku);
        apply(product, request);
        return ProductDTO.from(productRepository.save(product));
    }

    /** @throws ProductNotFoundException si el SKU no existe. */
    public ProductDTO update(String sku, ProductUpdateRequest request) {
        Product product = find(sku);
        apply(product, request);
        return ProductDTO.from(productRepository.save(product));
    }

    /** Borra el producto y su foto. Los pedidos ya creados conservan su propia copia de los datos. */
    public void delete(String sku) {
        Product product = find(sku);
        imageRepository.deleteById(product.getId());
        productRepository.delete(product);
    }

    /**
     * Reemplaza la foto. El formato se deduce de los bytes (JPEG, PNG o WebP), no del
     * Content-Type que declara el cliente.
     */
    public ProductDTO putImage(String sku, byte[] content) {
        if (content.length > MAX_IMAGE_BYTES) {
            throw new ImageTooLargeException(MAX_IMAGE_BYTES);
        }
        String contentType = detectImageType(content);
        if (contentType == null) {
            throw new UnsupportedImageException();
        }
        Product product = find(sku);
        imageRepository.save(new ProductImage(product.getId(), contentType, content));
        // Milisegundos: es la versión que ve el front (imageVersion) y cambia en cada subida.
        product.setImageUpdatedAt(Instant.now(clock).truncatedTo(ChronoUnit.MILLIS));
        return ProductDTO.from(productRepository.save(product));
    }

    /** @throws ImageNotFoundException si el producto existe pero no tiene foto. */
    @Transactional(readOnly = true)
    public ProductImage getImage(String sku) {
        Product product = find(sku);
        return imageRepository.findById(product.getId()).orElseThrow(() -> new ImageNotFoundException(sku));
    }

    public ProductDTO deleteImage(String sku) {
        Product product = find(sku);
        imageRepository.deleteById(product.getId());
        product.setImageUpdatedAt(null);
        return ProductDTO.from(productRepository.save(product));
    }

    static String detectImageType(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        int png = PNG_SIGNATURE.length;
        if (b.length >= png && Arrays.equals(b, 0, png, PNG_SIGNATURE, 0, png)) {
            return "image/png";
        }
        if (b.length >= 12 && ascii(b, 0).equals("RIFF") && ascii(b, 8).equals("WEBP")) {
            return "image/webp";
        }
        return null;
    }

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private Product find(String sku) {
        return productRepository.findFirstBySkuOrderByIdAsc(sku).orElseThrow(() -> new ProductNotFoundException(sku));
    }

    private static void apply(Product product, ProductFields fields) {
        product.setName(fields.name().strip());
        product.setTeam(fields.team().strip());
        product.setKitType(fields.kitType());
        product.setCategory(fields.category().strip());
        product.setLeague(blankToNull(fields.league()));
        product.setPrice(fields.price());
        product.setStock(fields.stock());
        product.setDescription(blankToNull(fields.description()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
