package com.camisetas360.notifications.security;

import com.camisetas360.notifications.controller.NotificationController;
import com.camisetas360.notifications.service.EmailService;
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

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JwtSecurityIT {

    private static final String PATH = "/api/v1/notifications/email";

    private static final String SCOPE = "Notifications.Send";

    private static final String ROLE = "ADMIN";

    private static final LocalJwtIssuer ISSUER = new LocalJwtIssuer();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtDecoder decoder;

    @MockitoBean
    private EmailService service;

    @DynamicPropertySource
    static void jwtProperties(
            DynamicPropertyRegistry properties) {

        properties.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                ISSUER::issuer);

        properties.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                ISSUER::jwksUri);

        properties.add(
                "spring.security.oauth2.resourceserver.jwt.audiences[0]",
                () -> LocalJwtIssuer.AUDIENCE);
    }

    @AfterAll
    static void stopIssuer() {
        ISSUER.close();
    }

    // JWT-NOT-001
    // JWT real firmado con:
    // - issuer correcto
    // - audience correcto
    // - firma válida
    // - scope Notifications.Send
    // - role ADMIN
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldAllowSignedJwt_whenIssuerAudienceSignatureScopeAndRoleAreValid(
            String path) throws Exception {

        assertThat(
                mockingDetails(decoder).isMock()).isFalse();

        mvc.perform(
                request(path)
                        .header(
                                "Authorization",
                                "Bearer "
                                        + ISSUER.token(
                                                SCOPE,
                                                ROLE,
                                                "valid")))
                .andExpect(
                        status().isOk())
                .andExpect(
                        jsonPath("$.status")
                                .value("SENT"));

        verify(service).sendEmail(
                "recipient@example.test",
                "Order",
                "Created");

        verifyNoMoreInteractions(service);

        assertThat(
                ISSUER.jwksRequests()).isPositive();
    }

    // JWT-NOT-002
    // Token inválido => 401.
    @ParameterizedTest
    @MethodSource("invalidTokens")
    void endpoint_shouldReturn401_whenTokenValidationFails(
            String path,
            String variant) throws Exception {

        mvc.perform(
                request(path)
                        .header(
                                "Authorization",
                                "Bearer "
                                        + ISSUER.token(
                                                SCOPE,
                                                ROLE,
                                                variant)))
                .andExpect(
                        status().isUnauthorized())
                .andExpect(
                        header().string(
                                "WWW-Authenticate",
                                org.hamcrest.Matchers
                                        .startsWith(
                                                "Bearer")))
                .andExpect(
                        jsonPath("$.status")
                                .doesNotExist());

        verifyNoInteractions(service);
    }

    // JWT-NOT-003
    // ADMIN correcto pero sin scope => 403.
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn403_whenSignedJwtHasAdminRoleButNoRequiredScope(
            String path) throws Exception {

        mvc.perform(
                request(path)
                        .header(
                                "Authorization",
                                "Bearer "
                                        + ISSUER.token(
                                                "",
                                                ROLE,
                                                "valid")))
                .andExpect(
                        status().isForbidden());

        verifyNoInteractions(service);
    }

    // JWT-NOT-004
    // Scope correcto pero sin ADMIN => 403.
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn403_whenSignedJwtHasScopeButNoAdminRole(
            String path) throws Exception {

        mvc.perform(
                request(path)
                        .header(
                                "Authorization",
                                "Bearer "
                                        + ISSUER.token(
                                                SCOPE,
                                                "",
                                                "valid")))
                .andExpect(
                        status().isForbidden());

        verifyNoInteractions(service);
    }

    // JWT-NOT-005
    // Rol incorrecto aunque el scope sea correcto => 403.
    @ParameterizedTest
    @MethodSource("protectedPaths")
    void endpoint_shouldReturn403_whenSignedJwtHasWrongRole(
            String path) throws Exception {

        mvc.perform(
                request(path)
                        .header(
                                "Authorization",
                                "Bearer "
                                        + ISSUER.token(
                                                SCOPE,
                                                "USER",
                                                "valid")))
                .andExpect(
                        status().isForbidden());

        verifyNoInteractions(service);
    }

    static Stream<String> protectedPaths() {
        return Stream.of(PATH);
    }

    static Stream<Arguments> invalidTokens() {

        return protectedPaths()
                .flatMap(
                        path -> Stream.of(
                                "audience",
                                "issuer",
                                "expired",
                                "signature",
                                "malformed")
                                .map(
                                        variant -> Arguments.of(
                                                path,
                                                variant)));
    }

    private static MockHttpServletRequestBuilder request(
            String path) {

        return post(path)
                .contentType(
                        MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "to": "recipient@example.test",
                          "subject": "Order",
                          "body": "Created"
                        }
                        """);
    }
}