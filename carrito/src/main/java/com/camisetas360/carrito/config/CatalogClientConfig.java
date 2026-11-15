package com.camisetas360.carrito.config;

import com.camisetas360.carrito.client.CatalogRestClient;
import com.camisetas360.carrito.service.CatalogoProductos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogClientConfig.CatalogClientProperties.class)
public class CatalogClientConfig {

    @Bean
    public CatalogoProductos catalogoProductos(CatalogClientProperties properties) {
        var http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.timeout());
        return new CatalogRestClient(RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build());
    }

    /** Ubicacion de ms-catalog. En ECS se inyecta CATALOG_BASE_URL. */
    @Validated
    @ConfigurationProperties("app.catalog")
    public record CatalogClientProperties(@NotBlank String baseUrl, @NotNull Duration timeout) {
    }
}
