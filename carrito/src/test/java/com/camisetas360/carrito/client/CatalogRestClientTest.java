package com.camisetas360.carrito.client;

import com.camisetas360.carrito.service.CatalogoNoDisponibleException;
import com.camisetas360.carrito.service.ProductoCatalogo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CatalogRestClientTest {

    private static final String URL_AB = "http://catalog/api/v1/catalog/products?sku=SKU-A&sku=SKU-B";
    private static final String BODY = """
            [{"sku":"SKU-A","name":"Camiseta Local","category":"Futbol","price":39.99,"stock":10,"description":null}]
            """;

    private MockRestServiceServer server;
    private CatalogRestClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://catalog");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogRestClient(builder.build());
        var jwt = Jwt.withTokenValue("user-token").header("alg", "none").subject("u").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void buscar_consultaPorLoteReenviandoElTokenDelUsuario_yOmiteLosInexistentes() {
        server.expect(requestTo(URL_AB)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        var productos = client.buscar(List.of("SKU-A", "SKU-B"));

        assertThat(productos).containsOnlyKeys("SKU-A");
        assertThat(productos.get("SKU-A")).isEqualTo(new ProductoCatalogo("SKU-A", "Camiseta Local", "Futbol", 39.99));
        server.verify();
    }

    @Test
    void buscar_reintentaUnaVezAnteErrorDeRed() {
        server.expect(requestTo(URL_AB)).andRespond(withException(new SocketTimeoutException("lento")));
        server.expect(requestTo(URL_AB)).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(client.buscar(List.of("SKU-A", "SKU-B"))).containsOnlyKeys("SKU-A");
        server.verify();
    }

    @Test
    void buscar_fallaComoNoDisponibleTrasAgotarReintentos_ante5xx() {
        server.expect(ExpectedCount.times(2), requestTo(URL_AB)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.buscar(List.of("SKU-A", "SKU-B")))
                .isInstanceOf(CatalogoNoDisponibleException.class);
        server.verify();
    }

    @Test
    void buscar_noReintentaErroresDelCliente() {
        server.expect(ExpectedCount.once(), requestTo(URL_AB)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.buscar(List.of("SKU-A", "SKU-B")))
                .isInstanceOf(CatalogoNoDisponibleException.class);
        server.verify();
    }

    @Test
    void buscar_sinSkusNoLlamaAlCatalogo() {
        assertThat(client.buscar(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void buscar_errorDeRedPersistenteEsNoDisponible() {
        server.expect(ExpectedCount.times(2), requestTo(URL_AB)).andRespond(withException(new IOException("down")));

        assertThatThrownBy(() -> client.buscar(List.of("SKU-A", "SKU-B")))
                .isInstanceOf(CatalogoNoDisponibleException.class);
    }
}
