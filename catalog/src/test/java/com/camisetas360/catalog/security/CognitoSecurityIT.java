package com.camisetas360.catalog.security;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.camisetas360.catalog.controller.ProductManagementController;
import com.camisetas360.catalog.service.ProductManagementService;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Administradores de Cognito gestionan el catalogo con tokens reales firmados (JWKS local), igual que los
 * de Entra; cualquier token de Cognito que no sea un access token de usuario del app client se rechaza.
 */
@WebMvcTest(ProductManagementController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CognitoSecurityIT {

    private static final String PRODUCT = "/api/v1/catalog/products/SKU-A";
    private static final LocalJwtIssuer ISSUER = new LocalJwtIssuer();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductManagementService service;

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

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CATALOG_MANAGER"})
    void cognitoManager_canManageCatalog(String group) throws Exception {
        mvc.perform(delete(PRODUCT).header("Authorization", bearer(ISSUER.cognitoToken(List.of(group), "valid"))))
                .andExpect(status().isNoContent());
        verify(service).delete("SKU-A");
    }

    @Test
    void cognitoCustomerOrUserWithoutGroup_isForbidden() throws Exception {
        mvc.perform(delete(PRODUCT).header("Authorization", bearer(ISSUER.cognitoToken(List.of("CUSTOMER"), "valid"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PRODUCT).header("Authorization", bearer(ISSUER.cognitoToken(null, "valid"))))
                .andExpect(status().isForbidden());
        // Grupos con otra capitalizacion no son roles.
        mvc.perform(delete(PRODUCT).header("Authorization", bearer(ISSUER.cognitoToken(List.of("admin"), "valid"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id-token", "other-client", "no-username", "wrong-issuer", "signature", "expired"})
    void invalidCognitoToken_isRejected(String variant) throws Exception {
        mvc.perform(delete(PRODUCT).header("Authorization", bearer(ISSUER.cognitoToken(List.of("ADMIN"), variant))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void entraManager_stillManagesCatalog() throws Exception {
        mvc.perform(delete(PRODUCT).header("Authorization", bearer(ISSUER.token("Catalog.Read", "role-ADMIN"))))
                .andExpect(status().isNoContent());
        mvc.perform(delete(PRODUCT).header("Authorization", bearer(ISSUER.token("Catalog.Read", "role-CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicRead_ignoresCognitoToken() throws Exception {
        org.mockito.Mockito.when(service.getImage("SKU-A"))
                .thenReturn(new com.camisetas360.catalog.models.ProductImage(1L, "image/png", new byte[] {1}));
        mvc.perform(get(PRODUCT + "/imagen").header("Authorization", bearer(ISSUER.cognitoToken(List.of("ADMIN"), "valid"))))
                .andExpect(status().isOk());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
