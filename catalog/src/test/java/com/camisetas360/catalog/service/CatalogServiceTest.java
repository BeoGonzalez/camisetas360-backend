package com.camisetas360.catalog.service;

import com.camisetas360.catalog.dtos.ProductDTO;
import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private ProductRepository repository;
    @InjectMocks
    private CatalogService service;

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
