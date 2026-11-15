package com.camisetas360.carrito.controller;

import com.camisetas360.carrito.dtos.AddCartItemRequest;
import com.camisetas360.carrito.dtos.CartDTO;
import com.camisetas360.carrito.dtos.ShippingRequest;
import com.camisetas360.carrito.model.ShippingMethod;
import com.camisetas360.carrito.security.EntraIdentity;
import com.camisetas360.carrito.security.SecurityConfig;
import com.camisetas360.carrito.service.CartItemNotFoundException;
import com.camisetas360.carrito.service.CartLimitExceededException;
import com.camisetas360.carrito.service.CartManagementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CartManagementController.class)
@Import(SecurityConfig.class)
class CartManagementControllerWebMvcTest {

    private static final EntraIdentity IDENTITY = new EntraIdentity(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final CartDTO CART = new CartDTO(
            List.of(new CartDTO.Item("SKU-A", "Camiseta", "Liga A", 20.0, 2, 40.0)),
            new CartDTO.Shipping("STANDARD", "Metropolitana", "Santiago", 3990.0), 40.0, 3990.0, 4030.0);
    private static final String ITEM_JSON = """
            {"sku":"SKU-A","nombre":"Camiseta","categoria":"Liga A","precioUnitario":20.0,"cantidad":2}
            """;
    private static final String SHIPPING_JSON = """
            {"metodo":"STANDARD","region":"Metropolitana","comuna":"Santiago"}
            """;

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean CartManagementService service;

    @Test
    void obtener_shouldReturnCartOfAuthenticatedUser() throws Exception {
        when(service.obtener(IDENTITY)).thenReturn(CART);

        mvc.perform(get("/api/v1/carrito").with(authorized()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$.envio.metodo").value("STANDARD"))
                .andExpect(jsonPath("$.total").value(4030.0));
    }

    @Test
    void agregarItem_shouldBindBodyAndIdentity() throws Exception {
        when(service.agregarItem(eq(IDENTITY), any())).thenReturn(CART);

        mvc.perform(post("/api/v1/carrito/items").with(authorized())
                        .contentType(MediaType.APPLICATION_JSON).content(ITEM_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(40.0));

        verify(service).agregarItem(IDENTITY, new AddCartItemRequest("SKU-A", "Camiseta", "Liga A", 20.0, 2));
    }

    @Test
    void agregarItem_shouldReturn400WithoutBusinessCall_whenBodyIsInvalid() throws Exception {
        mvc.perform(post("/api/v1/carrito/items").with(authorized()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"SKU A","nombre":"","precioUnitario":-1,"cantidad":100}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void agregarItem_shouldReturn422_whenLimitIsExceeded() throws Exception {
        when(service.agregarItem(eq(IDENTITY), any())).thenThrow(new CartLimitExceededException("SKU-A", 99));

        mvc.perform(post("/api/v1/carrito/items").with(authorized())
                        .contentType(MediaType.APPLICATION_JSON).content(ITEM_JSON))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void eliminarItem_shouldReturnUpdatedCart() throws Exception {
        when(service.eliminarItem(IDENTITY, "SKU-A")).thenReturn(CART);

        mvc.perform(delete("/api/v1/carrito/items/SKU-A").with(authorized()))
                .andExpect(status().isOk());
        verify(service).eliminarItem(IDENTITY, "SKU-A");
    }

    @Test
    void eliminarItem_shouldReturn404_whenSkuIsNotInCart() throws Exception {
        when(service.eliminarItem(IDENTITY, "SKU-X")).thenThrow(new CartItemNotFoundException("SKU-X"));

        mvc.perform(delete("/api/v1/carrito/items/SKU-X").with(authorized())).andExpect(status().isNotFound());
    }

    @Test
    void actualizarEnvio_shouldBindShippingChoice() throws Exception {
        when(service.actualizarEnvio(eq(IDENTITY), any())).thenReturn(CART);

        mvc.perform(put("/api/v1/carrito/envio").with(authorized())
                        .contentType(MediaType.APPLICATION_JSON).content(SHIPPING_JSON))
                .andExpect(status().isOk());
        verify(service).actualizarEnvio(IDENTITY, new ShippingRequest(ShippingMethod.STANDARD, "Metropolitana", "Santiago"));
    }

    @Test
    void actualizarEnvio_shouldReturn400_whenMethodIsUnknown() throws Exception {
        mvc.perform(put("/api/v1/carrito/envio").with(authorized()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"metodo":"DRON","region":"Metropolitana","comuna":"Santiago"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void endpoints_shouldReturn401_whenTokenIsMissing(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void endpoints_shouldReturn403_whenScopeIsMissing(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(jwt().jwt(token -> token
                                .claim("tid", IDENTITY.tenantId().toString()).claim("oid", IDENTITY.objectId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("protectedRequests")
    void endpoints_shouldReturn403_whenRoleIsMissing(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(jwt().jwt(token -> token
                                .claim("tid", IDENTITY.tenantId().toString()).claim("oid", IDENTITY.objectId().toString()))
                        .authorities(new SimpleGrantedAuthority("SCOPE_Checkout.Create"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    static Stream<MockHttpServletRequestBuilder> protectedRequests() {
        return Stream.of(
                get("/api/v1/carrito"),
                post("/api/v1/carrito/items").contentType(MediaType.APPLICATION_JSON).content(ITEM_JSON),
                delete("/api/v1/carrito/items/SKU-A"),
                put("/api/v1/carrito/envio").contentType(MediaType.APPLICATION_JSON).content(SHIPPING_JSON));
    }

    private static JwtRequestPostProcessor authorized() {
        return jwt().jwt(token -> token
                        .claim("tid", IDENTITY.tenantId().toString())
                        .claim("oid", IDENTITY.objectId().toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_Checkout.Create"), new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }
}
