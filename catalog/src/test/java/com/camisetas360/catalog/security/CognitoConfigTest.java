package com.camisetas360.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

class CognitoConfigTest {

    private static final String POOL = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Pool";

    @Test
    void disabledProperties_needNothingAndDefaultToManagerGroups() {
        var properties = new CognitoProperties(false, " ", null, null, null);
        assertThat(properties.issuerUri()).isNull();
        assertThat(properties.clientIds()).isEmpty();
        assertThat(properties.allowedGroups()).containsExactly("CATALOG_MANAGER", "ADMIN");
    }

    @Test
    void enabledProperties_deriveJwksAndCleanLists() {
        var properties = new CognitoProperties(true, POOL, null, java.util.Arrays.asList(" client ", null, "client", ""),
                List.of("ADMIN", " "));
        assertThat(properties.jwkSetUri()).isEqualTo(POOL + "/.well-known/jwks.json");
        assertThat(properties.clientIds()).containsExactly("client");
        assertThat(properties.allowedGroups()).containsExactly("ADMIN");
    }

    @Test
    void enabledProperties_failFastOnUnsafeOrIncompleteConfig() {
        assertInvalid(new Object[] {null, null, List.of("c"), null}, "issuer-uri es obligatorio");
        assertInvalid(new Object[] {"http://cognito.example.com/pool", null, List.of("c"), null}, "https");
        assertInvalid(new Object[] {POOL + "/", null, List.of("c"), null}, "no debe terminar");
        assertInvalid(new Object[] {"ht tp://bad", null, List.of("c"), null}, "no es una URL valida");
        assertInvalid(new Object[] {POOL, "https://user@host/jwks", List.of("c"), null}, "credenciales");
        assertInvalid(new Object[] {POOL, null, List.of(), null}, "client-ids");
        assertInvalid(new Object[] {POOL, null, List.of("c"), List.of(" ")}, "allowed-groups");
        assertThat(new CognitoProperties(true, "http://127.0.0.1:9/pool", "http://localhost:9/jwks", List.of("c"), null)
                .jwkSetUri()).isEqualTo("http://localhost:9/jwks");
    }

    @Test
    @SuppressWarnings("unchecked")
    void converter_mapsAllowedGroupsAndGrantsCatalogScope() {
        var converter = new CognitoAuthoritiesConverter(enabled());
        assertThat(names(converter.convert(jwt(Map.of("cognito:groups", List.of("ADMIN", "CUSTOMER", "Admin"))))))
                .containsExactlyInAnyOrder("SCOPE_Catalog.Read", "ROLE_ADMIN");
        assertThat(names(converter.convert(jwt(Map.of("cognito:groups", "CATALOG_MANAGER")))))
                .containsExactlyInAnyOrder("SCOPE_Catalog.Read", "ROLE_CATALOG_MANAGER");
        // roles/scp inyectados no cuentan: solo cognito:groups.
        assertThat(names(converter.convert(jwt(Map.of("roles", List.of("ADMIN"), "scp", "Catalog.Read")))))
                .containsExactly("SCOPE_Catalog.Read");
        assertThatThrownBy(() -> converter.convert(jwt(Map.of("cognito:groups", List.of(1)))))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(jwt(Map.of("cognito:groups", 7))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void validators_coverIssuerTimestampsAndCognitoClaims() {
        assertThat(CognitoJwtDecoders.validators(enabled())).hasSize(6);
        assertThat(CognitoJwtDecoders.create(enabled())).isNotNull();
    }

    @Test
    void issuerResolver_rejectsCognitoIssuerEqualToEntra() {
        assertThatThrownBy(() -> SecurityConfig.issuerResolver(POOL, mock(JwtDecoder.class),
                new JwtAuthenticationConverter(), enabled()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no puede coincidir");
        assertThat(SecurityConfig.issuerResolver("https://login.example/v2.0", mock(JwtDecoder.class),
                new JwtAuthenticationConverter(), enabled())).isNotNull();
    }

    private static CognitoProperties enabled() {
        return new CognitoProperties(true, POOL, null, List.of("client"), null);
    }

    private static void assertInvalid(Object[] v, String message) {
        assertThatThrownBy(() -> new CognitoProperties(true, (String) v[0], (String) v[1],
                (List<String>) cast(v[2]), (List<String>) cast(v[3])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static Jwt jwt(Map<String, Object> claims) {
        var builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static List<String> names(java.util.Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }
}
