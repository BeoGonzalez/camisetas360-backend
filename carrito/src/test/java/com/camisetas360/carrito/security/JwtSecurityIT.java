package com.camisetas360.carrito.security;

import com.camisetas360.carrito.controller.CartController;

import com.camisetas360.carrito.service.CartService;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.dtos.OrderItemDTO;
import com.camisetas360.carrito.dtos.CheckoutResponseDTO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CartController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtSecurityIT {

    private static final String PATH = "/api/v1/carrito/checkout";
    private static final String EMAIL = "buyer@example.test";
    private static final String SCOPE = "Checkout.Create";
    private static final LocalJwtIssuer ISSUER = new LocalJwtIssuer();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder decoder;

    @MockitoBean
    private CartService service;

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

    // JWT-CART-001: actual signed bearer and textual scp, without jwt() or a mocked decoder.
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldAllowSignedJwt_whenIssuerAudienceSignatureAndScopeAreValid(String path) throws Exception {
        when(service.createOrder(any())).thenReturn(new CheckoutResponseDTO(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), EMAIL, 0.01, "PROCESSING"));
        assertThat(mockingDetails(decoder).isMock()).isFalse();
        mvc.perform(request(path).header("Authorization", "Bearer " + ISSUER.token(SCOPE, "valid")))
                .andExpect(status().is(202))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        verify(service).createOrder(new OrderRequestDTO(List.of(new OrderItemDTO("SKU-A", 1, 0.01))));
        verifyNoMoreInteractions(service);
        assertThat(ISSUER.jwksRequests()).isPositive();
    }

    // JWT-CART-002
    @ParameterizedTest
    @MethodSource("invalidTokens")
    void endpoint_shouldReturn401_whenTokenValidationFails(String path, String variant) throws Exception {
        mvc.perform(request(path).header("Authorization", "Bearer " + ISSUER.token(SCOPE, variant)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.status").doesNotExist());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn403_whenSignedJwtHasNoRequiredScope(String path) throws Exception {
        mvc.perform(request(path).header("Authorization", "Bearer " + ISSUER.token("", "valid")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    static Stream<String> protectedPaths() {
        return Stream.of(PATH);
    }

    static Stream<Arguments> invalidTokens() {
        return protectedPaths().flatMap(path -> Stream.of("audience", "issuer", "expired", "signature", "malformed")
                .map(variant -> Arguments.of(path, variant)));
    }

    private static MockHttpServletRequestBuilder request(String path) {
        return post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"sku\":\"SKU-A\",\"quantity\":1,\"unitPrice\":0.01}]}");
    }
}
