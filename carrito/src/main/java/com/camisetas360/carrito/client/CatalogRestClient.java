package com.camisetas360.carrito.client;

import com.camisetas360.carrito.service.CatalogoNoDisponibleException;
import com.camisetas360.carrito.service.CatalogoProductos;
import com.camisetas360.carrito.service.ProductoCatalogo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptador HTTP hacia ms-catalog (lectura en tiempo real, permitida de forma sincrona).
 * Reenvia el JWT del usuario: catalog exige Catalog.Read, igual que cuando el frontend lo consulta.
 * Usa el filtro por lote ?sku=..., que no registra visitas de analitica.
 */
public class CatalogRestClient implements CatalogoProductos {

    private static final Logger log = LoggerFactory.getLogger(CatalogRestClient.class);
    private static final int INTENTOS = 2;
    private static final ParameterizedTypeReference<List<ProductoResponse>> LISTA = new ParameterizedTypeReference<>() { };

    private final RestClient rest;

    public CatalogRestClient(RestClient rest) {
        this.rest = rest;
    }

    @Override
    public Map<String, ProductoCatalogo> buscar(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        RestClientException ultimoError = null;
        for (int intento = 1; intento <= INTENTOS; intento++) {
            try {
                return consultar(skus);
            } catch (HttpClientErrorException clientError) {
                // 4xx no mejora reintentando (token sin permiso, request invalido).
                log.warn("Catalog rechazo la consulta de productos: {}", clientError.getStatusCode());
                throw new CatalogoNoDisponibleException(clientError);
            } catch (RestClientException error) {
                ultimoError = error;
                log.warn("Fallo consultando catalog (intento {}/{}): {}", intento, INTENTOS, error.getMessage());
            }
        }
        throw new CatalogoNoDisponibleException(ultimoError);
    }

    private Map<String, ProductoCatalogo> consultar(Collection<String> skus) {
        List<ProductoResponse> body = rest.get()
                .uri(uri -> uri.path("/api/v1/catalog/products").queryParam("sku", skus.toArray()).build())
                .headers(headers -> {
                    if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
                        headers.setBearerAuth(token.getToken().getTokenValue());
                    }
                })
                .retrieve()
                .body(LISTA);
        Map<String, ProductoCatalogo> productos = new LinkedHashMap<>();
        if (body != null) {
            body.stream().filter(p -> p.sku() != null && p.price() != null && skus.contains(p.sku()))
                    .forEach(p -> productos.putIfAbsent(p.sku(),
                            new ProductoCatalogo(p.sku(), p.name(), p.category(), p.price())));
        }
        return productos;
    }

    /** Contrato de respuesta de ms-catalog (ProductDTO). */
    record ProductoResponse(String sku, String name, String category, Double price) {
    }
}
