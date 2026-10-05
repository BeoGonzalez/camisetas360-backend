package com.camisetas360.catalog.repository;

import com.camisetas360.catalog.models.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import com.camisetas360.catalog.support.PostgresTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryTest extends PostgresTestSupport {

    @Autowired
    private ProductRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void isolateRepositoryFixturesFromVersionedSeed() {
        repository.deleteAll();
        repository.flush();
        entityManager.clear();
    }

    // JPA-CAT-001
    @Test
    void save_shouldPreserveAllFields_whenProductIsReloaded() {
        var product = product("SKU-A", "Camiseta local", 19.5, 12);
        var id = repository.saveAndFlush(product).getId();
        entityManager.clear();

        assertThat(id).isNotNull();
        var reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded).isNotSameAs(product);
        assertThat(reloaded.getId()).isEqualTo(id);
        assertThat(reloaded.getSku()).isEqualTo("SKU-A");
        assertThat(reloaded.getName()).isEqualTo("Camiseta local");
        assertThat(reloaded.getCategory()).isEqualTo("Futbol");
        assertThat(reloaded.getPrice()).isEqualTo(19.5);
        assertThat(reloaded.getStock()).isEqualTo(12);
        assertThat(reloaded.getDescription()).isEqualTo("Temporada 2026");
    }

    // JPA-CAT-002
    @Test
    void findAll_shouldReturnOnlyStoredProducts_whenProductsExist() {
        repository.save(product("SKU-A", "Local", 19.5, 12));
        repository.save(product("SKU-B", "Visita", 25.0, 7));
        repository.flush();
        entityManager.clear();

        assertThat(repository.findAll())
                .extracting(Product::getSku, Product::getName, Product::getPrice, Product::getStock)
                .containsExactlyInAnyOrder(
                        tuple("SKU-A", "Local", 19.5, 12),
                        tuple("SKU-B", "Visita", 25.0, 7));
    }

    // JPA-CAT-002
    @Test
    void findAll_shouldReturnEmptyList_whenDatabaseIsEmpty() {
        assertThat(repository.findAll()).isEmpty();
    }

    private static Product product(String sku, String name, double price, int stock) {
        var product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setCategory("Futbol");
        product.setPrice(price);
        product.setStock(stock);
        product.setDescription("Temporada 2026");
        return product;
    }
}
