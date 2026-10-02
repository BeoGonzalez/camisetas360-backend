package com.camisetas360.orders.controller;

import com.camisetas360.orders.security.SecurityConfig;
import com.camisetas360.orders.service.OrderService;
import com.camisetas360.orders.dto.OrderResponseDTO;
import com.camisetas360.orders.dto.OrderItemResponseDTO;
import com.camisetas360.orders.model.OrderStatus;
import com.camisetas360.orders.exception.OrderNotFoundException;
import com.camisetas360.orders.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

@WebMvcTest(controllers = OrderController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class OrderControllerWebMvcTest {

    private static final String PATH = "/api/v1/orders";
    private static final String EMAIL = "buyer@example.test";
    private static final String AUTHORITY = "SCOPE_Orders.Read";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private OrderService service;


    // MVC-ORD-001
    @Test
    void getMyOrders_shouldPassClaimAndSerializeOrders_whenAuthorized() throws Exception {
        when(service.findByUserEmail(EMAIL)).thenReturn(List.of(order()));
        mvc.perform(request(PATH).with(authorized()))
                .andExpect(status().isOk())
                .andExpect(content().json("[" + ORDER_JSON + "]"))
                .andExpect(jsonPath("$[0].items[0].order").doesNotExist());
        verify(service).findByUserEmail(EMAIL);
        verifyNoMoreInteractions(service);
    }

    // MVC-ORD-001
    @Test
    void getMyOrders_shouldReturnEmptyArray_whenUserHasNoOrders() throws Exception {
        when(service.findByUserEmail(EMAIL)).thenReturn(List.of());
        mvc.perform(request(PATH).with(authorized()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).findByUserEmail(EMAIL);
        verifyNoMoreInteractions(service);
    }

    // MVC-ORD-002
    @Test
    void getOrder_shouldPassIdAndClaim_whenAuthorized() throws Exception {
        when(service.findById(42L, EMAIL)).thenReturn(order());
        mvc.perform(request(PATH + "/42").with(authorized()))
                .andExpect(status().isOk())
                .andExpect(content().json(ORDER_JSON))
                .andExpect(jsonPath("$.items[0].order").doesNotExist());
        verify(service).findById(42L, EMAIL);
        verifyNoMoreInteractions(service);
    }

    // MVC-ORD-003: both service outcomes use the same exception and HTTP contract.
    @ParameterizedTest
    @ValueSource(longs = {42L, 99L})
    void getOrder_shouldReturn404WithoutOrderData_whenServiceRejectsLookup(long id) throws Exception {
        when(service.findById(id, EMAIL)).thenThrow(new OrderNotFoundException(id));
        mvc.perform(request(PATH + "/" + id).with(authorized()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Order not found: " + id))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.userEmail").doesNotExist())
                .andExpect(jsonPath("$.items").doesNotExist());
        verify(service).findById(id, EMAIL);
        verifyNoMoreInteractions(service);
    }

    // MVC-ORD-004
    @Test
    void getOrder_shouldReturn400_whenIdIsNotLong() throws Exception {
        mvc.perform(request(PATH + "/invalid").with(authorized()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static final String ORDER_JSON = """
            {"orderId":42,"userEmail":"buyer@example.test","totalAmount":39.0,
             "status":"CREATED","createdAt":"2026-01-01T12:00:00Z",
             "items":[{"sku":"SKU-A","quantity":2,"unitPrice":19.5}]}
            """;

    private static OrderResponseDTO order() {
        return new OrderResponseDTO(42L, EMAIL, 39.0, OrderStatus.CREATED,
                Instant.parse("2026-01-01T12:00:00Z"),
                List.of(new OrderItemResponseDTO("SKU-A", 2, 19.5)));
    }

    // SEC-ORD-001
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn401_whenTokenIsMissing(String path) throws Exception {
        mvc.perform(request(path)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
        verifyNoInteractions(service);
    }

    // SEC-ORD-002
    @ParameterizedTest
    @MethodSource("unauthorizedAuthorities")
    void endpoint_shouldReturn403_whenRequiredAuthorityIsMissing(String path, String authority) throws Exception {
        var token = authority.isEmpty() ? jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                : jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"), new SimpleGrantedAuthority(authority));
        mvc.perform(request(path).with(token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").doesNotExist());
        verifyNoInteractions(service);
    }

    // SEC-ORD-003
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
        return Stream.of(PATH, PATH + "/42");
    }

    @ParameterizedTest
    @MethodSource("unauthorizedRoles")
    void endpoint_shouldReturn403_whenScopeIsValidButRoleIsNot(String path, String role) throws Exception {
        var token = role.isEmpty() ? jwt().authorities(new SimpleGrantedAuthority(AUTHORITY))
                : jwt().authorities(new SimpleGrantedAuthority(AUTHORITY), new SimpleGrantedAuthority(role));
        mvc.perform(request(path).with(token)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    static Stream<Arguments> unauthorizedRoles() {
        return protectedPaths().flatMap(path -> Stream.of("", "ROLE_ADMIN")
                .map(role -> Arguments.of(path, role)));
    }

    static Stream<Arguments> unauthorizedAuthorities() {
        return protectedPaths().flatMap(path -> Stream.of(
                "", "SCOPE_Other.Read", "Orders.Read", "SCOPE_orders.read")
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
