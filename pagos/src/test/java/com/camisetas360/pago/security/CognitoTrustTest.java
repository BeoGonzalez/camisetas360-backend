package com.camisetas360.pago.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Entra ID + Cognito con tokens firmados reales (JWKS local) a traves de los mismos AuthenticationManager
 * por issuer que usa la API ({@link SecurityConfig#managers}).
 */
class CognitoTrustTest {

    private static final LocalJwtIssuer ISSUER = new LocalJwtIssuer();
    private static final String SCOPE = "SCOPE_Orders.Read";

    @AfterAll
    static void stop() {
        ISSUER.close();
    }

    private static CognitoProperties cognito(String defaultRole) {
        return new CognitoProperties(true, ISSUER.cognitoIssuer(), ISSUER.jwksUri(),
                List.of(LocalJwtIssuer.COGNITO_CLIENT),
                java.util.stream.Stream.of("CUSTOMER", defaultRole).filter(java.util.Objects::nonNull).distinct().toList(),
                defaultRole, List.of("Orders.Read"));
    }

    private static Map<String, AuthenticationManager> managers(CognitoProperties cognito) {
        var properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setIssuerUri(ISSUER.issuer());
        properties.getJwt().setJwkSetUri(ISSUER.jwksUri());
        properties.getJwt().setAudiences(List.of(LocalJwtIssuer.AUDIENCE));
        var entra = new JwtAuthenticationConverter();
        entra.setJwtGrantedAuthoritiesConverter(new JwtAuthoritiesConverter());
        return SecurityConfig.managers(ISSUER.issuer(), new JwtValidationConfig().jwtDecoder(properties), entra, cognito);
    }

    private static JwtAuthenticationToken autenticar(Map<String, AuthenticationManager> managers, String issuer,
            String token) {
        return (JwtAuthenticationToken) managers.get(issuer).authenticate(new BearerTokenAuthenticationToken(token));
    }

    private static List<String> nombres(java.util.Collection<GrantedAuthority> authorities) {
        // Spring Security 7 agrega FACTOR_BEARER a toda autenticacion por bearer token.
        return authorities.stream().map(GrantedAuthority::getAuthority).filter(a -> !a.startsWith("FACTOR_")).toList();
    }

    @Test
    void cognitoEnElGrupo_obtieneRolYScope_eIgnoraRolesInyectados() throws Exception {
        var managers = managers(cognito("CUSTOMER"));
        var auth = autenticar(managers, ISSUER.cognitoIssuer(), ISSUER.cognitoToken(List.of("CUSTOMER"), "valid"));
        assertThat(nombres(auth.getAuthorities())).containsExactlyInAnyOrder(SCOPE, "ROLE_CUSTOMER");
        // Mismo dueno que guarda carrito: (UUID v3 de "cognito:" + iss, sub).
        var identidad = EntraIdentity.from(auth.getToken());
        assertThat(identidad.objectId().toString()).isEqualTo(LocalJwtIssuer.COGNITO_SUB);
        assertThat(identidad.tenantId()).isEqualTo(java.util.UUID.nameUUIDFromBytes(
                ("cognito:" + ISSUER.cognitoIssuer()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Jwt sinIss = Jwt.withTokenValue("t").header("alg", "none").claim("token_use", "access")
                .subject(LocalJwtIssuer.COGNITO_SUB).build();
        assertThatThrownBy(() -> EntraIdentity.from(sinIss)).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void cognitoSinGrupo_recibeSoloElRolPorDefecto() throws Exception {
        var managers = managers(cognito("CUSTOMER"));
        var auth = autenticar(managers, ISSUER.cognitoIssuer(), ISSUER.cognitoToken(null, "valid"));
        assertThat(nombres(auth.getAuthorities())).containsExactlyInAnyOrder(SCOPE, "ROLE_CUSTOMER");
        var sinDefecto = managers(cognito(null));
        assertThat(nombres(autenticar(sinDefecto, ISSUER.cognitoIssuer(), ISSUER.cognitoToken(List.of("OTRO"), "valid"))
                .getAuthorities())).containsExactly(SCOPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id-token", "other-client", "no-username", "sub-not-uuid", "signature", "expired"})
    void tokenCognitoInvalido_seRechaza(String variant) throws Exception {
        var managers = managers(cognito("CUSTOMER"));
        String token = ISSUER.cognitoToken(List.of("CUSTOMER"), variant);
        assertThatThrownBy(() -> autenticar(managers, ISSUER.cognitoIssuer(), token))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void issuerDesconocido_noTieneManager_yEntraSigueFuncionando() throws Exception {
        var managers = managers(cognito("CUSTOMER"));
        assertThat(managers).containsOnlyKeys(ISSUER.issuer(), ISSUER.cognitoIssuer());
        var entra = autenticar(managers, ISSUER.issuer(), ISSUER.token("Orders.Read", "valid"));
        assertThat(nombres(entra.getAuthorities())).contains(SCOPE);
        // Un token de Cognito presentado al manager de Entra no pasa (issuer y audiencia distintos).
        String cognitoToken = ISSUER.cognitoToken(List.of("CUSTOMER"), "valid");
        assertThatThrownBy(() -> autenticar(managers, ISSUER.issuer(), cognitoToken))
                .isInstanceOf(AuthenticationException.class);

        var sinCognito = managers(new CognitoProperties(false, null, null, null, null, null, null));
        assertThat(sinCognito).containsOnlyKeys(ISSUER.issuer());
    }

    @Test
    void configuracionInsegura_fallaAlArrancar() {
        String pool = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_X";
        List<String> c = List.of("c");
        List<String> g = List.of("CUSTOMER", "ADMIN");
        assertThatThrownBy(() -> new CognitoProperties(true, null, null, c, g, null, null))
                .hasMessageContaining("issuer-uri es obligatorio");
        assertThatThrownBy(() -> new CognitoProperties(true, "http://cognito.example", null, c, g, null, null))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> new CognitoProperties(true, pool + "/", null, c, g, null, null))
                .hasMessageContaining("no debe terminar");
        assertThatThrownBy(() -> new CognitoProperties(true, pool, "https://x.example/j?a=1", c, g, null, null))
                .hasMessageContaining("query");
        assertThatThrownBy(() -> new CognitoProperties(true, pool, "::mal", c, g, null, null))
                .hasMessageContaining("URL valida");
        assertThatThrownBy(() -> new CognitoProperties(true, pool, null, List.of(" "), g, null, null))
                .hasMessageContaining("client-ids");
        assertThatThrownBy(() -> new CognitoProperties(true, pool, null, c, List.of(), null, null))
                .hasMessageContaining("allowed-groups");
        assertThatThrownBy(() -> new CognitoProperties(true, pool, null, c, List.of("CUSTOMER"), "OTRO", null))
                .hasMessageContaining("default-role debe estar");
        assertThatThrownBy(() -> new CognitoProperties(true, pool, null, c, g, "ADMIN", null))
                .hasMessageContaining("no puede ser ADMIN");
        var ok = new CognitoProperties(true, pool, null, c, g, " ", null);
        assertThat(ok.jwkSetUri()).isEqualTo(pool + "/.well-known/jwks.json");
        assertThat(ok.defaultRole()).isNull();
        assertThat(ok.scopes()).isEmpty();
        assertThatThrownBy(() -> SecurityConfig.managers(pool, null, null, new CognitoProperties(true, pool, null, c, g,
                null, null))).hasMessageContaining("no puede coincidir");
    }

    @Test
    void gruposConTipoInvalido_seRechazan() {
        var converter = new CognitoAuthoritiesConverter(cognito(null));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("cognito:groups", List.of(1)).build();
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(InvalidBearerTokenException.class);
        Jwt texto = Jwt.withTokenValue("t").header("alg", "none").claim("cognito:groups", "CUSTOMER").build();
        assertThat(nombres(converter.convert(texto))).contains("ROLE_CUSTOMER");
    }
}
