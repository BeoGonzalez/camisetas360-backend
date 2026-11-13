package com.camisetas360.catalog.service;

import com.camisetas360.catalog.dtos.ProductDTO;
import com.camisetas360.catalog.messaging.InteraccionEventPublisher;
import com.camisetas360.catalog.messaging.KafkaTopics;
import com.camisetas360.catalog.messaging.event.InteraccionEvento;
import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final String USUARIO = "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222";

    @Mock
    private ProductRepository repository;
    @Mock
    private InteraccionEventPublisher publisher;

    private CatalogService service;

    @BeforeEach
    void setUp() {
        service = new CatalogService(repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // UT-CAT-004
    @Test
    void getProductDetail_shouldReturnProductAndPublishProductViewed_whenSkuExists() {
        when(repository.findFirstBySkuOrderByIdAsc("SKU-A"))
                .thenReturn(Optional.of(product(2L, "SKU-A", "Camiseta roja", "Liga A", 19.5, 8, "Local")));

        assertThat(service.getProductDetail("SKU-A", USUARIO))
                .isEqualTo(new ProductDTO("SKU-A", "Camiseta roja", "Liga A", 19.5, 8, "Local"));

        var captor = ArgumentCaptor.forClass(InteraccionEvento.class);
        verify(publisher).publicar(eq(KafkaTopics.PRODUCTO_VISTO), captor.capture());
        var evento = captor.getValue();
        assertThat(evento.eventId()).isNotNull();
        assertThat(evento.eventType()).isEqualTo("PRODUCTO_VISTO");
        assertThat(evento.version()).isEqualTo(1);
        assertThat(evento.occurredAt()).isEqualTo(NOW.toString());
        assertThat(evento.source()).isEqualTo("catalog");
        assertThat(evento.usuarioId()).isEqualTo(USUARIO);
        assertThat(evento.producto())
                .isEqualTo(new InteraccionEvento.ProductoSnapshot("SKU-A", "Camiseta roja", "Liga A", 19.5));
        assertThat(evento.cantidad()).isNull();
        assertThat(evento.envio()).isNull();
    }

    // UT-CAT-005
    @Test
    void getProductDetail_shouldThrowWithoutPublishing_whenSkuDoesNotExist() {
        when(repository.findFirstBySkuOrderByIdAsc("SKU-X")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProductDetail("SKU-X", USUARIO))
                .isInstanceOf(ProductNotFoundException.class);

        verify(publisher, never()).publicar(any(), any());
    }

    // UT-CAT-001
    @Test
    void getAllProducts_shouldMapFieldsInRepositoryOrder_whenProductsExist() {
        var first = product(8L, "SKU-B", "Camiseta azul", "Liga B", 10.0, 3, null);
        var second = product(2L, "SKU-A", "Camiseta roja", "Liga A", 19.5, 8, "Edición local");
        when(repository.findAll()).thenReturn(List.of(first, second));

        assertThat(service.getAllProducts()).containsExactly(
                new ProductDTO("SKU-B", "Camiseta azul", "Liga B", 10.0, 3, null),
                new ProductDTO("SKU-A", "Camiseta roja", "Liga A", 19.5, 8, "Edición local"));

        assertThat(first).usingRecursiveComparison()
                .isEqualTo(product(8L, "SKU-B", "Camiseta azul", "Liga B", 10.0, 3, null));
        assertThat(second).usingRecursiveComparison()
                .isEqualTo(product(2L, "SKU-A", "Camiseta roja", "Liga A", 19.5, 8, "Edición local"));
        verify(repository).findAll();
        verifyNoMoreInteractions(repository);
    }

    // UT-CAT-002
    @Test
    void getAllProducts_shouldReturnEmptyList_whenCatalogIsEmpty() {
        when(repository.findAll()).thenReturn(List.of());

        assertThat(service.getAllProducts()).isEmpty();

        verify(repository).findAll();
        verifyNoMoreInteractions(repository);
    }

    // UT-CAT-003
    @Test
    void getAllProducts_shouldPropagateFailure_whenRepositoryFails() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(repository.findAll()).thenThrow(failure);

        assertThat(assertThrows(DataAccessResourceFailureException.class,
                service::getAllProducts)).isSameAs(failure);

        verify(repository).findAll();
        verifyNoMoreInteractions(repository);
    }

    // UT-CAT-006
    @Test
    void getProductsBySkus_shouldReturnOldestRecordPerSkuWithoutPublishingViews() {
        var oldA = product(1L, "SKU-A", "Camiseta roja", "Liga A", 19.5, 8, null);
        var newA = product(9L, "SKU-A", "Duplicada", "Liga A", 1.0, 1, null);
        var b = product(4L, "SKU-B", "Camiseta azul", "Liga B", 10.0, 3, null);
        when(repository.findBySkuInOrderByIdAsc(List.of("SKU-A", "SKU-B", "SKU-X")))
                .thenReturn(List.of(oldA, b, newA));

        assertThat(service.getProductsBySkus(List.of("SKU-A", "SKU-B", "SKU-A", "SKU-X"))).containsExactly(
                new ProductDTO("SKU-A", "Camiseta roja", "Liga A", 19.5, 8, null),
                new ProductDTO("SKU-B", "Camiseta azul", "Liga B", 10.0, 3, null));
        verifyNoInteractions(publisher);
    }

    private static Product product(Long id, String sku, String name, String category,
                                   double price, int stock, String description) {
        var product = new Product();
        product.setId(id);
        product.setSku(sku);
        product.setName(name);
        product.setCategory(category);
        product.setPrice(price);
        product.setStock(stock);
        product.setDescription(description);
        return product;
    }
}
