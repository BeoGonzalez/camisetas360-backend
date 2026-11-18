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
        // Real Flyway seed and real startup runner: the 3 V2 products, the 60 Premier League kits of V3 and
        // the 60 Serie A kits of V6 and the 60 La Liga kits of V7 (the Datafaker runner must not add anything on top).
        assertThat(context.getBean(ProductRepository.class).findAll())
                .extracting(Product::getSku)
                .hasSize(183)
                .contains("CAM-001", "CAM-002", "CAM-003", "PL-ARS-LOC-2627", "PL-TOT-ALT-2627",
                        "SA-INT-LOC-2627", "SA-SAS-ALT-2627", "LL-ATH-LOC-2627", "LL-VIL-ALT-2627");
    }
}
