package com.camisetas360.notifications.controller;

import com.camisetas360.notifications.security.SecurityConfig;
import com.camisetas360.notifications.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerWebMvcTest {

    private static final String PATH = "/api/v1/notifications/email";
    private static final String EMAIL = "buyer@example.test";

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String NOTIFICATIONS_SEND_SCOPE = "SCOPE_Notifications.Send";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private EmailService service;

    // MVC-NOT-001
    @Test
    void sendEmail_shouldReturnSent_whenRequestIsValid() throws Exception {

        mvc.perform(request(PATH)
                .with(authorized()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "status": "SENT",
                          "to": "recipient@example.test"
                        }
                        """));

        verify(service).sendEmail(
                "recipient@example.test",
                "Order",
                "Created");

        verifyNoMoreInteractions(service);
    }

    // MVC-NOT-002
    @ParameterizedTest
    @MethodSource("invalidBodies")
    void sendEmail_shouldReturn400_whenRequestFieldIsInvalid(
            String body) throws Exception {

        assertInvalidBody(body);
    }

    static Stream<String> invalidBodies() {
        return Stream.of(
                "{\"to\":null,\"subject\":\"Order\",\"body\":\"Created\"}",
                "{\"to\":\"\",\"subject\":\"Order\",\"body\":\"Created\"}",
                "{\"to\":\" \",\"subject\":\"Order\",\"body\":\"Created\"}",
                "{\"to\":\"invalid\",\"subject\":\"Order\",\"body\":\"Created\"}",
                "{\"to\":\"recipient@example.test\",\"subject\":null,\"body\":\"Created\"}",
                "{\"to\":\"recipient@example.test\",\"subject\":\"\",\"body\":\"Created\"}",
                "{\"to\":\"recipient@example.test\",\"subject\":\" \",\"body\":\"Created\"}",
                "{\"to\":\"recipient@example.test\",\"subject\":\"Order\",\"body\":null}",
                "{\"to\":\"recipient@example.test\",\"subject\":\"Order\",\"body\":\"\"}",
                "{\"to\":\"recipient@example.test\",\"subject\":\"Order\",\"body\":\" \"}");
    }

    // MVC-NOT-003
    @ParameterizedTest
    @ValueSource(strings = { "", "{" })
    void sendEmail_shouldReturn400_whenBodyIsMissingOrMalformed(
            String body) throws Exception {

        assertInvalidBody(body);
    }

    private void assertInvalidBody(String body) throws Exception {

        mvc.perform(post(PATH)
                .with(authorized())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    // SEC-NOT-001
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn401_whenTokenIsMissing(
            String path) throws Exception {

        mvc.perform(request(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        "WWW-Authenticate",
                        org.hamcrest.Matchers.startsWith("Bearer")));

        verifyNoInteractions(service);
    }

    // SEC-NOT-002
    @ParameterizedTest
    @MethodSource("unauthorizedAuthorities")
    void endpoint_shouldReturn403_whenRequiredAuthorityIsMissing(
            String path,
            List<GrantedAuthority> authorities) throws Exception {

        var token = jwt().authorities(authorities);

        mvc.perform(request(path)
                .with(token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").doesNotExist());

        verifyNoInteractions(service);
    }

    // SEC-NOT-003
    @Test
    void security_shouldDenyUnknownRouteAndDisallowedMethod_whenJwtIsFullyAuthorized()
            throws Exception {

        mvc.perform(get("/__test_denied__")
                .with(authorized()))
                .andExpect(status().isForbidden());

        mvc.perform(delete(PATH)
                .with(authorized()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void options_shouldBePublic_withoutGrantingCorsHeaders()
            throws Exception {

        mvc.perform(options(PATH))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(
                        "Access-Control-Allow-Origin"));

        verifyNoInteractions(service);
    }

    static Stream<String> protectedPaths() {
        return Stream.of(PATH);
    }

    static Stream<Arguments> unauthorizedAuthorities() {

        return Stream.of(
                Arguments.of(
                        PATH,
                        authorities()),

                // Tiene scope correcto, pero falta ROLE_ADMIN.
                Arguments.of(
                        PATH,
                        authorities(
                                NOTIFICATIONS_SEND_SCOPE)),

                // Tiene ROLE_ADMIN, pero falta scope.
                Arguments.of(
                        PATH,
                        authorities(
                                ROLE_ADMIN)),

                // Rol incorrecto + scope correcto.
                Arguments.of(
                        PATH,
                        authorities(
                                "ROLE_USER",
                                NOTIFICATIONS_SEND_SCOPE)),

                // ROLE_ADMIN + scope incorrecto.
                Arguments.of(
                        PATH,
                        authorities(
                                ROLE_ADMIN,
                                "SCOPE_Other.Read")),

                // ROLE_ADMIN + authority sin prefijo SCOPE_.
                Arguments.of(
                        PATH,
                        authorities(
                                ROLE_ADMIN,
                                "Notifications.Send")),

                // ROLE_ADMIN + scope con casing incorrecto.
                Arguments.of(
                        PATH,
                        authorities(
                                ROLE_ADMIN,
                                "SCOPE_notifications.send")));
    }

    /**
     * Crea authorities tipadas como GrantedAuthority para evitar
     * problemas con la invariancia genérica de List<SimpleGrantedAuthority>.
     */
    private static List<GrantedAuthority> authorities(
            String... authorityNames) {

        return Stream.of(authorityNames)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    /**
     * JWT completamente autorizado según SecurityConfig:
     *
     * hasRole("ADMIN")
     * -> ROLE_ADMIN
     *
     * hasAuthority("SCOPE_Notifications.Send")
     * -> SCOPE_Notifications.Send
     */
    private static JwtRequestPostProcessor authorized() {

        return jwt()
                .jwt(token -> token
                        .claim("preferred_username", EMAIL)
                        .claim("oid", "user-1")
                        .claim("tid", "tenant-1")
                        .claim("name", "Buyer"))
                .authorities(authorities(
                        ROLE_ADMIN,
                        NOTIFICATIONS_SEND_SCOPE));
    }

    private static MockHttpServletRequestBuilder request(
            String path) {

        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "to": "recipient@example.test",
                          "subject": "Order",
                          "body": "Created"
                        }
                        """);
    }
}