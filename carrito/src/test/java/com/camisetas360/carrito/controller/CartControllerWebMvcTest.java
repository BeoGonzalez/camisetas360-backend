package com.camisetas360.carrito.controller;

import com.camisetas360.carrito.security.SecurityConfig;
import com.camisetas360.carrito.service.CartService;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.dtos.OrderItemDTO;
import com.camisetas360.carrito.dtos.CheckoutResponseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@WebMvcTest(controllers = CartController.class)
@Import(SecurityConfig.class)
class CartControllerWebMvcTest {

    private static final String PATH = "/api/v1/carrito/checkout";
    private static final String EMAIL = "buyer@example.test";
    private static final String AUTHORITY = "SCOPE_Checkout.Create";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private CartService service;


    // MVC-CART-001
    @Test
    void checkout_shouldReturnAcceptedAndBindRequest_whenValid() throws Exception {
        var response = new CheckoutResponseDTO(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                EMAIL, 0.01, "PROCESSING");
        when(service.createOrder(any())).thenReturn(response);
        mvc.perform(request(PATH).with(authorized()))
                .andExpect(status().isAccepted())
                .andExpect(content().json("""
                        {"requestId":"00000000-0000-0000-0000-000000000001",
                         "userEmail":"buyer@example.test","totalAmount":0.01,"status":"PROCESSING"}
                        """));
        verify(service).createOrder(new OrderRequestDTO(List.of(new OrderItemDTO("SKU-A", 1, 0.01))));
        verifyNoMoreInteractions(service);
    }

    // MVC-CART-002
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"items\":null}", "{\"items\":[]}"})
    void checkout_shouldReturn400_whenItemsAreMissingOrEmpty(String body) throws Exception {
        assertInvalidBody(body);
    }

    // MVC-CART-003: each violation is tested alone and after a valid first item.
    @ParameterizedTest
    @MethodSource("invalidItems")
    void checkout_shouldReturn400_whenAnyItemIsInvalid(String body) throws Exception {
        assertInvalidBody(body);
    }

    static Stream<String> invalidItems() {
        return Stream.of(
                "{\"items\":[{\"sku\":null,\"quantity\":1,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01},{\"sku\":null,\"quantity\":1,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"\",\"quantity\":1,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01},{\"sku\":\"\",\"quantity\":1,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\" \",\"quantity\":1,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01},{\"sku\":\" \",\"quantity\":1,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":null,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01},{\"sku\":\"SKU-A\",\"quantity\":null,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":0,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01},{\"sku\":\"SKU-A\",\"quantity\":0,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":-1,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01},{\"sku\":\"SKU-A\",\"quantity\":-1,\"unitPrice\":0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":null}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01},{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":null}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01},{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":-0.01}]}",
                "{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01},{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":-0.01}]}");
    }

    // MVC-CART-004
    @ParameterizedTest
    @ValueSource(strings = {"", "{"})
    void checkout_shouldReturn400_whenBodyIsMissingOrMalformed(String body) throws Exception {
        assertInvalidBody(body);
    }

    private void assertInvalidBody(String body) throws Exception {
        mvc.perform(post(PATH).with(authorized()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    // SEC-CART-001
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn401_whenTokenIsMissing(String path) throws Exception {
        mvc.perform(request(path)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
        verifyNoInteractions(service);
    }

    // SEC-CART-002
    @ParameterizedTest
    @MethodSource("unauthorizedAuthorities")
    void endpoint_shouldReturn403_whenRequiredAuthorityIsMissing(String path, String authority) throws Exception {
        var token = authority.isEmpty() ? jwt().authorities(List.of())
                : jwt().authorities(new SimpleGrantedAuthority(authority));
        mvc.perform(request(path).with(token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").doesNotExist());
        verifyNoInteractions(service);
    }

    // SEC-CART-003
    @Test
    void security_shouldDenyUnknownRouteAndDisallowedMethod_whenJwtHasRequiredScope() throws Exception {
        mvc.perform(get("/__test_denied__").with(authorized())).andExpect(status().isForbidden());
        mvc.perform(delete(PATH).with(authorized())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void options_shouldBePublic_withoutGrantingCorsHeaders() throws Exception {
        mvc.perform(options(PATH)).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(service);
    }

    static Stream<String> protectedPaths() {
        return Stream.of(PATH);
    }

    static Stream<Arguments> unauthorizedAuthorities() {
        return protectedPaths().flatMap(path -> Stream.of(
                "", "SCOPE_Other.Read", "Checkout.Create", "SCOPE_checkout.create")
                .map(authority -> Arguments.of(path, authority)));
    }

    private static JwtRequestPostProcessor authorized() {
        return jwt().jwt(token -> token
                        .claim("preferred_username", EMAIL)
                        .claim("oid", "user-1").claim("tid", "tenant-1").claim("name", "Buyer"))
                .authorities(new SimpleGrantedAuthority(AUTHORITY));
    }

    private static MockHttpServletRequestBuilder request(String path) {
        return post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01}]}");
    }
}
