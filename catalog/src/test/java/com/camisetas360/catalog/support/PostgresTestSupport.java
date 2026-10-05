package com.camisetas360.catalog.support;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One disposable database per test class; Docker failures fail the suite. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresTestSupport {
    private static PostgreSQLContainer postgres;

    @DynamicPropertySource
    static synchronized void databaseProperties(DynamicPropertyRegistry registry) {
        if (postgres == null || !postgres.isRunning()) {
            postgres = new PostgreSQLContainer("postgres:17")
                    .withDatabaseName("catalog_test");
            postgres.start();
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @AfterAll
    static synchronized void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }
}
