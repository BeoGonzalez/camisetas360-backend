package com.camisetas360.carrito.controller;

import com.camisetas360.carrito.messaging.CheckoutEventPublisher;
import com.camisetas360.carrito.repository.CheckoutRequestRepository;
import com.camisetas360.carrito.security.SecurityConfig;
import com.camisetas360.carrito.service.CartService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CartController.class)
@Import({SecurityConfig.class, CartService.class})
class CartContactWebMvcTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean CheckoutEventPublisher publisher;
    @MockitoBean CheckoutRequestRepository checkouts;

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor token(String email) {
        return jwt().jwt(builder -> builder.claim("tid", "11111111-1111-1111-1111-111111111111")
            .claim("oid", "22222222-2222-2222-2222-222222222222").claim("email", email))
            .authorities(new SimpleGrantedAuthority("SCOPE_Checkout.Create"), new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test void absentContactProducesControlled422WithoutPublishing() throws Exception {
        mvc.perform(post("/api/v1/carrito/checkout").with(token(null)).contentType("application/json")
            .content("{\"items\":[{\"sku\":\"A\",\"quantity\":1,\"unitPrice\":20}]}"))
            .andExpect(status().isUnprocessableContent());
        verifyNoInteractions(publisher, checkouts);
    }

    @Test void emailClaimWorksWithoutPreferredUsername() throws Exception {
        mvc.perform(post("/api/v1/carrito/checkout").with(token("contact@example.test"))
            .contentType("application/json")
            .content("{\"items\":[{\"sku\":\"A\",\"quantity\":1,\"unitPrice\":20}]}"))
            .andExpect(status().isAccepted());
        verify(publisher).publishCheckoutRequested(any());
    }
}
