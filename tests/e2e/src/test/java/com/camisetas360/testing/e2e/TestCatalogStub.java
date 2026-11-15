package com.camisetas360.testing.e2e;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stub de ms-catalog para el E2E: carrito valida precios con GET /api/v1/catalog/products?sku=...
 * Devuelve solo los SKU conocidos, con los mismos precios que usa el flujo de checkout.
 */
final class TestCatalogStub implements AutoCloseable {

    private static final Map<String, String> PRODUCTOS = Map.of(
            "CAM-Ñ-東京", "{\"sku\":\"CAM-Ñ-東京\",\"name\":\"Camiseta Tokio\",\"category\":\"Coleccion\",\"price\":19.5,\"stock\":10,\"description\":null}",
            "CAM-002", "{\"sku\":\"CAM-002\",\"name\":\"Camiseta Visitante\",\"category\":\"Futbol\",\"price\":10.0,\"stock\":10,\"description\":null}");

    private final HttpServer server;

    TestCatalogStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/catalog/products", exchange -> {
            try (exchange) {
                String query = exchange.getRequestURI().getRawQuery();
                String body = query == null ? "[]" : Arrays.stream(query.split("&"))
                        .filter(param -> param.startsWith("sku="))
                        .map(param -> URLDecoder.decode(param.substring(4), StandardCharsets.UTF_8))
                        .distinct()
                        .map(PRODUCTOS::get)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.joining(",", "[", "]"));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
