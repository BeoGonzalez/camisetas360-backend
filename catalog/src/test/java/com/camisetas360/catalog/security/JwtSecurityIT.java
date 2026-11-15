package com.camisetas360.catalog.security;

import com.camisetas360.catalog.controller.CatalogController;

import com.camisetas360.catalog.service.CatalogService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CatalogController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtSecurityIT {

    private static final String PATH = "/api/v1/catalog/products";
    private static final String SCOPE = "Catalog.Read";
    private static final LocalJwtIssuer ISSUER = new LocalJwtIssuer();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder decoder;

    @MockitoBean
    private CatalogService service;

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
        properties.add("spring.security.oauth2.resourceserver.jwt.audiences[0]", () -> LocalJwtIssuer.AUDIENCE);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    // JWT-CAT-001: actual signed bearer and textual scp, without jwt() or a mocked decoder.
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldAllowSignedJwt_whenIssuerAudienceSignatureAndScopeAreValid(String path) throws Exception {
        when(service.getAllProducts()).thenReturn(List.of());
        assertThat(mockingDetails(decoder).isMock()).isFalse();
        mvc.perform(request(path).header("Authorization", "Bearer " + ISSUER.token(SCOPE, "valid")))
                .andExpect(status().is(200))
                .andExpect(content().json("[]"));
        verify(service).getAllProducts();
        verifyNoMoreInteractions(service);
        assertThat(ISSUER.jwksRequests()).isPositive();
    }

    // JWT-CAT-002: un token de Entra invalido se rechaza aunque la lectura sea publica.
    @ParameterizedTest
    @MethodSource("invalidTokens")
    void endpoint_shouldReturn401_whenEntraTokenValidationFails(String path, String variant) throws Exception {
        mvc.perform(request(path).header("Authorization", "Bearer " + ISSUER.token(SCOPE, variant)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.status").doesNotExist());
        verifyNoInteractions(service);
    }

    // JWT-CAT-003: tokens de otro proveedor (Cognito) o ilegibles se ignoran en la lectura publica.
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"issuer", "malformed"})
    void publicRead_shouldIgnoreForeignOrMalformedTokens(String variant) throws Exception {
        when(service.getAllProducts()).thenReturn(List.of());
        mvc.perform(request(PATH).header("Authorization", "Bearer " + ISSUER.token(SCOPE, variant)))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.api.Test
    void publicRead_shouldWorkWithoutTokenAndWithoutScope() throws Exception {
        when(service.getAllProducts()).thenReturn(List.of());
        mvc.perform(request(PATH)).andExpect(status().isOk());
        mvc.perform(request(PATH).header("Authorization", "Bearer " + ISSUER.token("", "valid")))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.api.Test
    void eachConfiguredRoleCanRead() throws Exception {
        when(service.getAllProducts()).thenReturn(java.util.List.of());
        for (String role : List.of("CUSTOMER", "ADMIN", "CATALOG_MANAGER")) {
            String token = ISSUER.token(SCOPE, "role-" + role);
            assertThat(decoder.decode(token).getClaimAsStringList("roles")).containsExactly(role);
            mvc.perform(request(PATH).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }
    }

    // Las escrituras siguen exigiendo un token de Entra valido con rol de gestor.
    @org.junit.jupiter.api.Test
    void writes_shouldStillRequireValidEntraManagerToken() throws Exception {
        mvc.perform(delete(PATH + "/SKU-A")).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH + "/SKU-A").header("Authorization", "Bearer " + ISSUER.token(SCOPE, "issuer")))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH + "/SKU-A").header("Authorization", "Bearer " + ISSUER.token(SCOPE, "valid")))
                .andExpect(status().isForbidden());
        mvc.perform(delete(PATH + "/SKU-A").header("Authorization", "Bearer " + ISSUER.token(SCOPE, "role-Admin")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @org.junit.jupiter.api.Test
    void productionPreflightIsHandledWithoutBackendCorsHeaders() throws Exception {
        mvc.perform(options(PATH).header("Origin", "https://frontend.example.test")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        verifyNoInteractions(service);
    }

    static Stream<String> protectedPaths() {
        return Stream.of(PATH);
    }

    static Stream<Arguments> invalidTokens() {
        return protectedPaths().flatMap(path -> Stream.of("audience", "expired", "future-nbf", "missing-exp", "signature")
                .map(variant -> Arguments.of(path, variant)));
    }

    private static MockHttpServletRequestBuilder request(String path) {
        return get(path);
    }
}
