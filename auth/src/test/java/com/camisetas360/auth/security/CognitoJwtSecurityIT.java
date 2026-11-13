package com.camisetas360.auth.security;

import com.camisetas360.auth.config.SecurityConfig;
import com.camisetas360.auth.config.TrustedIssuers;
import com.camisetas360.auth.controller.AuthController;
import com.camisetas360.auth.repository.UserProfileRepository;
import com.camisetas360.auth.service.UserProfileService;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.camisetas360.auth.security.LocalCognitoIssuer.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Entra ID + Cognito habilitados a la vez, con tokens firmados reales y dos JWKS en loopback. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, UserProfileService.class})
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CognitoJwtSecurityIT {

    private static final String PATH = "/api/v1/auth/profile";
    private static final LocalJwtIssuer ENTRA = new LocalJwtIssuer();
    private static final LocalCognitoIssuer COGNITO = new LocalCognitoIssuer();

    @Autowired private MockMvc mvc;
    @Autowired private TrustedIssuers trustedIssuers;
    @MockitoBean private UserProfileRepository profiles;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ENTRA::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ENTRA::jwksUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences[0]", () -> LocalJwtIssuer.AUDIENCE);
        registry.add("app.security.cognito.enabled", () -> "true");
        registry.add("app.security.cognito.issuer-uri", COGNITO::issuer);
        // Sin jwk-set-uri: se deriva de <issuer>/.well-known/jwks.json.
        registry.add("app.security.cognito.client-ids", () -> CLIENT_ID + ",otro-cliente");
        registry.add("app.security.cognito.resource-server-id", () -> RESOURCE_SERVER);
        registry.add("app.security.cognito.allowed-groups", () -> "CUSTOMER,CATALOG_MANAGER");
        registry.add("app.security.cognito.default-role", () -> "CUSTOMER");
        registry.add("app.security.cognito.user-info-uri", COGNITO::userInfoUri);
        registry.add("app.security.cognito.user-info-timeout", () -> "500ms");
    }

    @AfterEach
    void resetUserInfo() {
        COGNITO.userInfoMode(UserInfoMode.OK);
    }

    @AfterAll
    static void stopIssuers() {
        ENTRA.close();
        COGNITO.close();
    }

    @Test
    void entraToken_stillReturns200_withHistoricalMapping() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + ENTRA.token("Profile.Read", "valid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.tenantId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.email").value("buyer@example.test"));
        verify(profiles).upsert(ENTRA.issuer(), "22222222-2222-2222-2222-222222222222",
                "22222222-2222-2222-2222-222222222222", "11111111-1111-1111-1111-111111111111",
                "buyer@example.test", "Buyer");
        verify(profiles, never()).upsertKeepingContact(any(), any(), any(), any(), any(), any());
    }

    @Test
    void cognitoCustomer_returns200_andPersistsByIssuerAndSub() throws Exception {
        int before = COGNITO.userInfoRequests();
        mvc.perform(get(PATH).header("Authorization", "Bearer " + COGNITO.token(claims -> { })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(SUB))
                .andExpect(jsonPath("$.tenantId").value(POOL_ID))
                .andExpect(jsonPath("$.email").value("cliente@example.test"))
                .andExpect(jsonPath("$.name").value("Cliente Externo"));
        verify(profiles).upsertKeepingContact(COGNITO.issuer(), SUB, SUB, POOL_ID, "cliente@example.test", "Cliente Externo");
        assertThat(COGNITO.userInfoRequests()).as("con email/name en el token no se llama a userInfo").isEqualTo(before);
    }

    @Test
    void cognitoWithoutGroups_returns200_withDefaultRole() throws Exception {
        String token = COGNITO.token(claims -> claims.put("cognito:groups", null));
        assertThat(authorities(token)).containsExactlyInAnyOrder("SCOPE_Profile.Read", "ROLE_CUSTOMER");
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void cognitoAdminGroup_isIgnoredBecauseItIsNotAllowed() throws Exception {
        assertThat(authorities(COGNITO.token(claims -> claims.put("cognito:groups", List.of("ADMIN")))))
                .containsExactlyInAnyOrder("SCOPE_Profile.Read", "ROLE_CUSTOMER");
        assertThat(authorities(COGNITO.token(claims -> claims.put("cognito:groups", List.of("ADMIN", "CATALOG_MANAGER")))))
                .containsExactlyInAnyOrder("SCOPE_Profile.Read", "ROLE_CATALOG_MANAGER");
    }

    @Test
    void cognitoInjectedEntraClaims_doNotGrantRolesOrScopes() throws Exception {
        String token = COGNITO.token(claims -> {
            claims.put("roles", List.of("ADMIN"));
            claims.put("scp", "Profile.Read");
            claims.put("scope", "openid");
        });
        assertThat(authorities(token)).containsExactlyInAnyOrder("ROLE_CUSTOMER");
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("scopesWithoutProfileRead")
    void cognitoWithoutProfileReadScope_returns403(String scope) throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + COGNITO.token(claims -> claims.put("scope", scope))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(profiles);
    }

    static Stream<String> scopesWithoutProfileRead() {
        return Stream.of("openid email", "Profile.Read", "otra-api/Profile.Read", RESOURCE_SERVER + "/Orders.Read",
                RESOURCE_SERVER + "/profile.read", "aws.cognito.signin.user.admin");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCognitoTokens")
    void invalidCognitoToken_returns401(String description, Consumer<Map<String, Object>> customizer) throws Exception {
        expectUnauthorized(COGNITO.token(customizer));
    }

    static Stream<Arguments> invalidCognitoTokens() {
        return Stream.of(
                invalid("token_use=id", c -> c.put("token_use", "id")),
                invalid("sin token_use", c -> c.put("token_use", null)),
                invalid("client_id incorrecto", c -> c.put("client_id", "cliente-desconocido")),
                invalid("sin client_id", c -> c.put("client_id", null)),
                invalid("client_id no textual", c -> c.put("client_id", List.of(CLIENT_ID))),
                invalid("M2M sin username", c -> c.put("username", null)),
                invalid("sin sub", c -> c.put("sub", null)),
                invalid("expirado", c -> c.put("exp", Date.from(Instant.parse("2021-01-01T00:00:00Z")))),
                invalid("sin exp", c -> c.put("exp", null)),
                invalid("iss con '/' final", c -> c.put("iss", c.get("iss") + "/")),
                invalid("iss con distinto case", c -> c.put("iss", ((String) c.get("iss")).replace("TestPool", "testpool"))),
                invalid("grupos no textuales", c -> c.put("cognito:groups", List.of(42))));
    }

    private static Arguments invalid(String description, Consumer<Map<String, Object>> customizer) {
        return Arguments.of(description, customizer);
    }

    @Test
    void cognitoTokenWithWrongSignature_returns401() throws Exception {
        expectUnauthorized(COGNITO.tokenSignedWithWrongKey());
    }

    @Test
    void cognitoKeyWithEntraIssuer_returns401() throws Exception {
        expectUnauthorized(COGNITO.sign(entraClaims(ENTRA.issuer())));
    }

    @Test
    void entraKeyWithCognitoIssuer_returns401() throws Exception {
        var claims = new JWTClaimsSet.Builder();
        accessTokenClaims(COGNITO.issuer()).forEach(claims::claim);
        expectUnauthorized(ENTRA.sign(claims.build()));
    }

    @Test
    void unknownIssuer_returns401() throws Exception {
        expectUnauthorized(COGNITO.token(c -> c.put("iss", "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Otro")));
        expectUnauthorized(COGNITO.token(c -> c.put("iss", null)));
        expectUnauthorized("not-a-jwt");
    }

    @Test
    void missingEmailAndName_useUserInfoFallback_withTheCallersBearer() throws Exception {
        String token = COGNITO.token(c -> { c.put("email", null); c.put("name", null); });
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("fallback@example.test"))
                .andExpect(jsonPath("$.name").value("Fallback"));
        assertThat(COGNITO.lastUserInfoAuthorization()).isEqualTo("Bearer " + token);
        verify(profiles).upsertKeepingContact(COGNITO.issuer(), SUB, SUB, POOL_ID, "fallback@example.test", "Fallback");
    }

    @ParameterizedTest
    @EnumSource(value = UserInfoMode.class, names = {"ERROR", "SLOW", "REDIRECT", "NOT_JSON"})
    void userInfoFailure_stillReturns200_withoutContact(UserInfoMode mode) throws Exception {
        COGNITO.userInfoMode(mode);
        mvc.perform(get(PATH).header("Authorization", "Bearer "
                        + COGNITO.token(c -> { c.put("email", null); c.put("name", null); })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(SUB))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.name").doesNotExist());
        verify(profiles).upsertKeepingContact(COGNITO.issuer(), SUB, SUB, POOL_ID, null, null);
    }

    @Test
    void onlyEntraAndCognitoAreTrusted() {
        assertThat(trustedIssuers.issuers()).containsExactly(ENTRA.issuer(), COGNITO.issuer());
    }

    private void expectUnauthorized(String token) throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(jsonPath("$.email").doesNotExist());
        verify(profiles, never()).upsert(anyString(), anyString(), any(), any(), any(), any());
        verify(profiles, never()).upsertKeepingContact(anyString(), anyString(), any(), any(), any(), any());
    }

    /** Autoridades calculadas por el AuthenticationManager real del issuer (decoder + validadores + converter). */
    private List<String> authorities(String token) throws Exception {
        String issuer = com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        return trustedIssuers.authenticationManager(issuer)
                .authenticate(new BearerTokenAuthenticationToken(token))
                .getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(authority -> !authority.startsWith("FACTOR_")) // Spring Security 7 agrega FACTOR_BEARER.
                .toList();
    }

    private static JWTClaimsSet entraClaims(String issuer) {
        return new JWTClaimsSet.Builder().issuer(issuer).audience(LocalJwtIssuer.AUDIENCE)
                .subject("22222222-2222-2222-2222-222222222222")
                .claim("oid", "22222222-2222-2222-2222-222222222222")
                .claim("tid", "11111111-1111-1111-1111-111111111111")
                .claim("scp", "Profile.Read").claim("roles", List.of("ADMIN"))
                .issueTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2100-01-01T00:00:00Z"))).build();
    }
}
