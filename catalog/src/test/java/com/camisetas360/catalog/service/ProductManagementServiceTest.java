package com.camisetas360.catalog.service;

import com.camisetas360.catalog.dtos.ProductCreateRequest;
import com.camisetas360.catalog.dtos.ProductUpdateRequest;
import com.camisetas360.catalog.models.KitType;
import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.models.ProductImage;
import com.camisetas360.catalog.repository.ProductImageRepository;
import com.camisetas360.catalog.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00.123456Z");
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0};
    private static final byte[] WEBP = "RIFF0000WEBPVP8 ".getBytes();

    @Mock
    private ProductRepository products;
    @Mock
    private ProductImageRepository images;

    private ProductManagementService service;

    @BeforeEach
    void setUp() {
        service = new ProductManagementService(products, images, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void create_shouldPersistTrimmedFields_whenSkuIsFree() {
        when(products.findFirstBySkuOrderByIdAsc("PL-ARS-LOC-2728")).thenReturn(Optional.empty());
        when(products.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        var dto = service.create(new ProductCreateRequest("PL-ARS-LOC-2728", " Camiseta Arsenal Local ", " Arsenal ",
                KitType.LOCAL, "Fútbol", " ", 94.99, 10, ""));

        var saved = ArgumentCaptor.forClass(Product.class);
        verify(products).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Camiseta Arsenal Local");
        assertThat(saved.getValue().getTeam()).isEqualTo("Arsenal");
        assertThat(saved.getValue().getLeague()).isNull();
        assertThat(saved.getValue().getDescription()).isNull();
        assertThat(dto.kitType()).isEqualTo("LOCAL");
        assertThat(dto.imageVersion()).isNull();
    }

    @Test
    void create_shouldRejectDuplicateSku() {
        when(products.findFirstBySkuOrderByIdAsc("SKU-A")).thenReturn(Optional.of(product(1L, "SKU-A")));

        assertThatThrownBy(() -> service.create(new ProductCreateRequest("SKU-A", "N", "T", KitType.LOCAL,
                "Fútbol", null, 1.0, 1, null))).isInstanceOf(ProductAlreadyExistsException.class);
        verify(products, never()).save(any());
    }

    @Test
    void update_shouldReplaceEditableFieldsAndKeepSku() {
        var existing = product(1L, "SKU-A");
        when(products.findFirstBySkuOrderByIdAsc("SKU-A")).thenReturn(Optional.of(existing));
        when(products.save(existing)).thenReturn(existing);

        var dto = service.update("SKU-A", new ProductUpdateRequest("Nuevo", "Chelsea", KitType.ALTERNATIVA,
                "Fútbol", "Premier League", 50.0, 0, "Desc"));

        assertThat(dto.sku()).isEqualTo("SKU-A");
        assertThat(dto.stock()).isZero();
        assertThat(dto.team()).isEqualTo("Chelsea");
        assertThat(dto.kitType()).isEqualTo("ALTERNATIVA");
    }

    @Test
    void update_shouldThrowNotFound_whenSkuDoesNotExist() {
        when(products.findFirstBySkuOrderByIdAsc("SKU-X")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("SKU-X", new ProductUpdateRequest("N", "T", KitType.LOCAL,
                "Fútbol", null, 1.0, 1, null))).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void delete_shouldRemoveImageAndProduct() {
        var existing = product(7L, "SKU-A");
        when(products.findFirstBySkuOrderByIdAsc("SKU-A")).thenReturn(Optional.of(existing));

        service.delete("SKU-A");

        verify(images).deleteById(7L);
        verify(products).delete(existing);
    }

    @Test
    void putImage_shouldStoreDetectedTypeAndBumpVersionInMillis() {
        var existing = product(7L, "SKU-A");
        when(products.findFirstBySkuOrderByIdAsc("SKU-A")).thenReturn(Optional.of(existing));
        when(products.save(existing)).thenReturn(existing);

        var dto = service.putImage("SKU-A", PNG);

        var saved = ArgumentCaptor.forClass(ProductImage.class);
        verify(images).save(saved.capture());
        assertThat(saved.getValue().getProductId()).isEqualTo(7L);
        assertThat(saved.getValue().getContentType()).isEqualTo("image/png");
        assertThat(dto.imageVersion()).isEqualTo(Instant.parse("2026-11-14T12:00:00.123Z").toEpochMilli());
    }

    @Test
    void putImage_shouldRejectNonImagesBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> service.putImage("SKU-A", "<svg></svg>".getBytes()))
                .isInstanceOf(UnsupportedImageException.class);
        assertThatThrownBy(() -> service.putImage("SKU-A", new byte[ProductManagementService.MAX_IMAGE_BYTES + 1]))
                .isInstanceOf(ImageTooLargeException.class);
        verifyNoInteractions(products, images);
    }

    @Test
    void detectImageType_shouldRecognizeJpegPngAndWebp() {
        assertThat(ProductManagementService.detectImageType(JPEG)).isEqualTo("image/jpeg");
        assertThat(ProductManagementService.detectImageType(PNG)).isEqualTo("image/png");
        assertThat(ProductManagementService.detectImageType(WEBP)).isEqualTo("image/webp");
        assertThat(ProductManagementService.detectImageType(new byte[0])).isNull();
        assertThat(ProductManagementService.detectImageType("GIF89a".getBytes())).isNull();
    }

    @Test
    void getImage_shouldThrow_whenProductHasNoImage() {
        when(products.findFirstBySkuOrderByIdAsc("SKU-A")).thenReturn(Optional.of(product(7L, "SKU-A")));
        when(images.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getImage("SKU-A")).isInstanceOf(ImageNotFoundException.class);
    }

    @Test
    void deleteImage_shouldClearVersion() {
        var existing = product(7L, "SKU-A");
        existing.setImageUpdatedAt(NOW);
        when(products.findFirstBySkuOrderByIdAsc("SKU-A")).thenReturn(Optional.of(existing));
        when(products.save(existing)).thenReturn(existing);

        var dto = service.deleteImage("SKU-A");

        verify(images).deleteById(7L);
        assertThat(dto.imageVersion()).isNull();
    }

    private static Product product(Long id, String sku) {
        var p = new Product();
        p.setId(id);
        p.setSku(sku);
        p.setName("Camiseta");
        p.setCategory("Fútbol");
        p.setPrice(10.0);
        p.setStock(5);
        return p;
    }
}
