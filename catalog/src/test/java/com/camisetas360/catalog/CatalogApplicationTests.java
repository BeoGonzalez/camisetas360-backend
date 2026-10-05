package com.camisetas360.catalog;

import com.camisetas360.catalog.support.PostgresTestSupport;

import com.camisetas360.catalog.controller.CatalogController;
import com.camisetas360.catalog.repository.ProductRepository;
import com.camisetas360.catalog.models.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;



import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate"
})
class CatalogApplicationTests extends PostgresTestSupport {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    // IT-CFG-001 and IT-CAT-001
    @Test
    void contextLoads() {
        assertThat(context.getBean(CatalogController.class)).isNotNull();
        // Real data.sql and real startup runner: the seed must remain the three SQL products.
        assertThat(context.getBean(ProductRepository.class).findAll())
                .extracting(Product::getSku)
                .containsExactlyInAnyOrder("CAM-001", "CAM-002", "CAM-003");
    }
}
