package com.camisetas360.auth.controller;

import com.camisetas360.auth.config.SecurityConfig;

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

@WebMvcTest(controllers = AuthController.class, properties = "app.cors.allowed-origin-patterns[0]=https://frontend.example.test")
@Import(SecurityConfig.class)
class AuthControllerWebMvcTest {

    private static final String PATH = "/api/v1/auth/profile";
    private static final String EMAIL = "buyer@example.test";
    private static final String AUTHORITY = "SCOPE_Profile.Read";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;


    // MVC-AUTH-001
    @Test
    void profile_shouldSerializeClaims_whenAuthorized() throws Exception {
        mvc.perform(request(PATH).with(authorized()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"userId":"user-1","tenantId":"tenant-1",
                         "email":"buyer@example.test","name":"Buyer"}
                        """));
    }

    // SEC-AUTH-001
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn401_whenTokenIsMissing(String path) throws Exception {
        mvc.perform(request(path)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    // SEC-AUTH-002
    @ParameterizedTest
    @MethodSource("unauthorizedAuthorities")
    void endpoint_shouldReturn403_whenRequiredAuthorityIsMissing(String path, String authority) throws Exception {
        var token = authority.isEmpty() ? jwt().authorities(List.of())
                : jwt().authorities(new SimpleGrantedAuthority(authority));
        mvc.perform(request(path).with(token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.email").doesNotExist());
        
    }

    // SEC-AUTH-003
    @Test
    void security_shouldDenyUnknownRouteAndDisallowedMethod_whenJwtHasRequiredScope() throws Exception {
        mvc.perform(get("/__test_denied__").with(authorized())).andExpect(status().isForbidden());
        mvc.perform(delete(PATH).with(authorized())).andExpect(status().isForbidden());
        
    }

    // CORS-AUTH-001
    @Test
    void preflight_shouldAllowConfiguredOriginAndHeaders_withoutToken() throws Exception {
        mvc.perform(options(PATH)
                        .header("Origin", "https://frontend.example.test")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://frontend.example.test"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,OPTIONS"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Content-Type")))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        
    }

    // CORS-AUTH-001
    @Test
    void preflight_shouldRejectUnconfiguredOrigin_withoutBusinessCall() throws Exception {
        mvc.perform(options(PATH)
                        .header("Origin", "https://untrusted.example.test")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        
    }

    static Stream<String> protectedPaths() {
        return Stream.of(PATH);
    }

    static Stream<Arguments> unauthorizedAuthorities() {
        return protectedPaths().flatMap(path -> Stream.of(
                "", "SCOPE_Other.Read", "Profile.Read", "SCOPE_profile.read")
                .map(authority -> Arguments.of(path, authority)));
    }

    private static JwtRequestPostProcessor authorized() {
        return jwt().jwt(token -> token
                        .claim("preferred_username", EMAIL)
                        .claim("oid", "user-1").claim("tid", "tenant-1").claim("name", "Buyer"))
                .authorities(new SimpleGrantedAuthority(AUTHORITY));
    }

    private static MockHttpServletRequestBuilder request(String path) {
        return get(path);
    }
}
