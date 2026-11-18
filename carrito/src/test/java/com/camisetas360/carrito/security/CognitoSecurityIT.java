package com.camisetas360.carrito.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.camisetas360.carrito.controller.CartManagementController;
import com.camisetas360.carrito.dtos.AddCartItemRequest;
import com.camisetas360.carrito.service.CartManagementService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Clientes del login propio (Cognito) usan el carrito con tokens reales firmados (JWKS local); el dueno
 * del carrito es (UUID del user pool, sub). Cualquier otro token de Cognito se rechaza con 401.
 */
@WebMvcTest(CartManagementController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CognitoSecurityIT {

    private static final String CART = "/api/v1/carrito";
    private static final String ITEM = """
            {"sku":"SA-INT-LOC-2627","nombre":"Inter Local","categoria":"Fútbol","precioUnitario":94990.0,"cantidad":1}
            """;
    private static final LocalJwtIssuer ISSUER = new LocalJwtIssuer();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CartManagementService service;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
        properties.add("spring.security.oauth2.resourceserver.jwt.audiences[0]", () -> LocalJwtIssuer.AUDIENCE);
        properties.add("app.security.cognito.enabled", () -> "true");
        properties.add("app.security.cognito.issuer-uri", ISSUER::cognitoIssuer);
        properties.add("app.security.cognito.jwk-set-uri", ISSUER::jwksUri);
        properties.add("app.security.cognito.client-ids", () -> LocalJwtIssuer.COGNITO_CLIENT);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    private static EntraIdentity cliente() {
        return new EntraIdentity(EntraIdentity.cognitoTenant(ISSUER.cognitoIssuer()),
                UUID.fromString(LocalJwtIssuer.COGNITO_SUB));
    }

    @Test
    void clienteCognitoEnGrupoCustomer_veYAgregaAlCarrito() throws Exception {
        String token = bearer(ISSUER.cognitoToken(List.of("CUSTOMER"), "valid"));
        mvc.perform(get(CART).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post(CART + "/items").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(ITEM))
                .andExpect(status().isOk());
        verify(service).obtener(cliente());
        verify(service).agregarItem(eq(cliente()), any(AddCartItemRequest.class));
    }

    @Test
    void clienteCognitoSinGrupo_recibeElRolPorDefectoCustomer() throws Exception {
        mvc.perform(get(CART).header("Authorization", bearer(ISSUER.cognitoToken(null, "valid"))))
                .andExpect(status().isOk());
        verify(service).obtener(cliente());
    }

    @Test
    void grupoNoPermitido_noDaRolPropio_peroRecibeElPorDefecto() throws Exception {
        // Igual que auth: un grupo fuera de la allowlist no es rol; el usuario queda como CUSTOMER.
        mvc.perform(get(CART).header("Authorization", bearer(ISSUER.cognitoToken(List.of("ADMIN"), "valid"))))
                .andExpect(status().isOk());
        verify(service).obtener(cliente());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id-token", "other-client", "no-username", "sub-not-uuid", "wrong-issuer", "signature",
            "expired"})
    void tokenCognitoInvalido_esRechazado(String variant) throws Exception {
        mvc.perform(get(CART).header("Authorization", bearer(ISSUER.cognitoToken(List.of("CUSTOMER"), variant))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void clienteEntra_siguePudiendoUsarElCarrito() throws Exception {
        mvc.perform(get(CART).header("Authorization", bearer(ISSUER.token("Checkout.Create", "valid"))))
                .andExpect(status().isOk());
        verify(service).obtener(new EntraIdentity(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222")));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
