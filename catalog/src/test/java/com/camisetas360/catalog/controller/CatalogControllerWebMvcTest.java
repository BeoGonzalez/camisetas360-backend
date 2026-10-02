package com.camisetas360.catalog.controller;

import com.camisetas360.catalog.security.SecurityConfig;
import com.camisetas360.catalog.service.CatalogService;
import com.camisetas360.catalog.dtos.ProductDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest(controllers = CatalogController.class, properties = "app.cors.allowed-origin-patterns[0]=https://frontend.example.test")
@Import(SecurityConfig.class)
class CatalogControllerWebMvcTest {

    private static final String PATH = "/api/v1/catalog/products";
    private static final String EMAIL = "buyer@example.test";
    private static final String AUTHORITY = "SCOPE_Catalog.Read";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private CatalogService service;


    // MVC-CAT-001
    @Test
    void products_shouldSerializeProductFields_whenAuthorized() throws Exception {
        when(service.getAllProducts()).thenReturn(List.of(
                new ProductDTO("SKU-A", "Local", "Futbol", 19.5, 12, "Temporada")));
        mvc.perform(request(PATH).with(authorized()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [{"sku":"SKU-A","name":"Local","category":"Futbol",
                          "price":19.5,"stock":12,"description":"Temporada"}]
                        """))
                .andExpect(jsonPath("$[0].id").doesNotExist());
        verify(service).getAllProducts();
        verifyNoMoreInteractions(service);
    }

    // MVC-CAT-002
    @Test
    void products_shouldReturnEmptyArray_whenCatalogIsEmpty() throws Exception {
        when(service.getAllProducts()).thenReturn(List.of());
        mvc.perform(request(PATH).with(authorized()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).getAllProducts();
        verifyNoMoreInteractions(service);
    }

    // SEC-CAT-001
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn401_whenTokenIsMissing(String path) throws Exception {
        mvc.perform(request(path)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
        verifyNoInteractions(service);
    }

    // SEC-CAT-002
    @ParameterizedTest
    @MethodSource("unauthorizedAuthorities")
    void endpoint_shouldReturn403_whenRequiredAuthorityIsMissing(String path, String authority) throws Exception {
        var token = authority.isEmpty() ? jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                : jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"), new SimpleGrantedAuthority(authority));
        mvc.perform(request(path).with(token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").doesNotExist());
        verifyNoInteractions(service);
    }

    // SEC-CAT-003
    @Test
    void security_shouldDenyUnknownRouteAndDisallowedMethod_whenJwtHasRequiredScope() throws Exception {
        mvc.perform(get("/__test_denied__").with(authorized())).andExpect(status().isForbidden());
        mvc.perform(delete(PATH).with(authorized())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    // CORS-CAT-001
    @Test
    void preflight_shouldAllowConfiguredOriginAndHeaders_withoutToken() throws Exception {
        mvc.perform(options(PATH)
                        .header("Origin", "https://frontend.example.test")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type,Accept"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://frontend.example.test"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,OPTIONS"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Content-Type")))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Accept")))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        verifyNoInteractions(service);
    }

    // CORS-CAT-001
    @Test
    void preflight_shouldRejectUnconfiguredOrigin_withoutBusinessCall() throws Exception {
        mvc.perform(options(PATH)
                        .header("Origin", "https://untrusted.example.test")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(service);
    }

    static Stream<String> protectedPaths() {
        return Stream.of(PATH);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "ROLE_OTHER"})
    void products_shouldReturn403_whenScopeIsValidButRoleIsNot(String role) throws Exception {
        var token = role.isEmpty() ? jwt().authorities(new SimpleGrantedAuthority(AUTHORITY))
                : jwt().authorities(new SimpleGrantedAuthority(AUTHORITY), new SimpleGrantedAuthority(role));
        mvc.perform(request(PATH).with(token)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    static Stream<Arguments> unauthorizedAuthorities() {
        return protectedPaths().flatMap(path -> Stream.of(
                "", "SCOPE_Other.Read", "Catalog.Read", "SCOPE_catalog.read")
                .map(authority -> Arguments.of(path, authority)));
    }

    private static JwtRequestPostProcessor authorized() {
        return jwt().jwt(token -> token
                        .claim("preferred_username", EMAIL)
                        .claim("oid", "user-1").claim("tid", "tenant-1").claim("name", "Buyer"))
                .authorities(new SimpleGrantedAuthority(AUTHORITY), new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static MockHttpServletRequestBuilder request(String path) {
        return get(path);
    }
}
