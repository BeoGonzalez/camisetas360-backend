package com.camisetas360.orders.security;

import com.camisetas360.orders.controller.OrderController;

import com.camisetas360.orders.service.OrderService;
import com.camisetas360.orders.dto.OrderResponseDTO;
import com.camisetas360.orders.dto.OrderItemResponseDTO;
import com.camisetas360.orders.model.OrderStatus;
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

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtSecurityIT {

    private static final String PATH = "/api/v1/orders";
    private static final String EMAIL = "buyer@example.test";
    private static final String SCOPE = "Orders.Read";
    private static final LocalJwtIssuer ISSUER = new LocalJwtIssuer();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder decoder;

    @MockitoBean
    private OrderService service;

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

    // JWT-ORD-001: actual signed bearer and textual scp, without jwt() or a mocked decoder.
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldAllowSignedJwt_whenIssuerAudienceSignatureAndScopeAreValid(String path) throws Exception {
        if (path.endsWith("/42")) {
            when(service.findById(42L, EMAIL)).thenReturn(new OrderResponseDTO(42L, EMAIL, 39.0,
                    OrderStatus.CREATED, Instant.parse("2026-01-01T12:00:00Z"),
                    List.of(new OrderItemResponseDTO("SKU-A", 2, 19.5))));
        } else {
            when(service.findByUserEmail(EMAIL)).thenReturn(List.of());
        }
        assertThat(mockingDetails(decoder).isMock()).isFalse();
        var result = mvc.perform(request(path).header("Authorization", "Bearer " + ISSUER.token(SCOPE, "valid")))
                .andExpect(status().is(200))
                ;
        if (path.endsWith("/42")) {
            result.andExpect(jsonPath("$.orderId").value(42))
                    .andExpect(jsonPath("$.items[0].sku").value("SKU-A"));
            verify(service).findById(42L, EMAIL);
        } else {
            result.andExpect(content().json("[]"));
            verify(service).findByUserEmail(EMAIL);
        }
        verifyNoMoreInteractions(service);
        assertThat(ISSUER.jwksRequests()).isPositive();
    }

    // JWT-ORD-002
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
        return Stream.of(PATH, PATH + "/42");
    }

    static Stream<Arguments> invalidTokens() {
        return protectedPaths().flatMap(path -> Stream.of("audience", "issuer", "expired", "signature", "malformed")
                .map(variant -> Arguments.of(path, variant)));
    }

    private static MockHttpServletRequestBuilder request(String path) {
        return get(path);
    }
}
