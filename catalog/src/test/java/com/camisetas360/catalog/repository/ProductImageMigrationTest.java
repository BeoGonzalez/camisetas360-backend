package com.camisetas360.catalog.repository;

import com.camisetas360.catalog.models.KitType;
import com.camisetas360.catalog.models.Product;
import com.camisetas360.catalog.models.ProductImage;
import com.camisetas360.catalog.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Migracion V4: equipo y tipo de las camisetas existentes, y fotos en product_images. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.defer-datasource-initialization=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductImageMigrationTest extends PostgresTestSupport {

    @Autowired
    private ProductRepository products;

    @Autowired
    private ProductImageRepository images;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void migration_shouldBackfillTeamAndKitType_forPremierLeagueKits() {
        var local = products.findFirstBySkuOrderByIdAsc("PL-BHA-LOC-2627").orElseThrow();
        var visitante = products.findFirstBySkuOrderByIdAsc("PL-MUN-VIS-2627").orElseThrow();
        var alternativa = products.findFirstBySkuOrderByIdAsc("PL-ARS-ALT-2627").orElseThrow();

        assertThat(local.getTeam()).isEqualTo("Brighton & Hove Albion");
        assertThat(local.getKitType()).isEqualTo(KitType.LOCAL);
        assertThat(visitante.getTeam()).isEqualTo("Manchester United");
        assertThat(visitante.getKitType()).isEqualTo(KitType.VISITANTE);
        assertThat(alternativa.getTeam()).isEqualTo("Arsenal");
        assertThat(alternativa.getKitType()).isEqualTo(KitType.ALTERNATIVA);
        assertThat(products.findAll()).filteredOn(p -> "Premier League".equals(p.getLeague()))
                .allSatisfy(p -> {
                    assertThat(p.getTeam()).isNotBlank();
                    assertThat(p.getKitType()).isNotNull();
                    assertThat(p.getImageUpdatedAt()).isNull();
                });
    }

    @Test
    void migration_shouldStorePricesAsWholeChileanPesos() {
        assertThat(products.findFirstBySkuOrderByIdAsc("PL-ARS-LOC-2627").orElseThrow().getPrice())
                .isEqualTo(94990.0);
        assertThat(products.findFirstBySkuOrderByIdAsc("CAM-001").orElseThrow().getPrice())
                .isEqualTo(39990.0);
        assertThat(products.findAll()).allSatisfy(p -> {
            assertThat(p.getPrice()).isGreaterThanOrEqualTo(1000.0);
            assertThat(p.getPrice() % 1).isZero();
        });
    }

    @Test
    void image_shouldRoundTripBytes_andBeDeletedWithItsProduct() {
        var product = new Product();
        product.setSku("TEST-IMG");
        product.setName("Camiseta de prueba");
        product.setCategory("Fútbol");
        product.setPrice(10.0);
        product.setStock(1);
        product.setTeam("Equipo");
        product.setKitType(KitType.ALTERNATIVA);
        product.setImageUpdatedAt(Instant.parse("2026-11-14T12:00:00.123Z"));
        Long id = products.saveAndFlush(product).getId();
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        images.saveAndFlush(new ProductImage(id, "image/jpeg", bytes));
        entityManager.clear();

        assertThat(images.findById(id)).get().satisfies(img -> {
            assertThat(img.getContent()).isEqualTo(bytes);
            assertThat(img.getContentType()).isEqualTo("image/jpeg");
        });
        assertThat(products.findById(id).orElseThrow().getImageUpdatedAt())
                .isEqualTo(Instant.parse("2026-11-14T12:00:00.123Z"));

        products.deleteById(id);
        products.flush();
        entityManager.clear();
        assertThat(images.findById(id)).isEmpty();
    }
}
