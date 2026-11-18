package com.camisetas360.carrito.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class CognitoConfigTest {

    private static final String POOL = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_jZ9MPGJGI";
    private static final String SUB = "33333333-3333-3333-3333-333333333333";

    private static CognitoProperties props(List<String> groups, String defaultRole) {
        return new CognitoProperties(true, POOL, null, List.of("client"), groups, defaultRole);
    }

    @Test
    void propiedades_porDefecto() {
        var p = props(null, null);
        assertThat(p.allowedGroups()).containsExactly("CUSTOMER");
        assertThat(p.defaultRole()).isEqualTo("CUSTOMER");
        assertThat(p.jwkSetUri()).isEqualTo(POOL + "/.well-known/jwks.json");
        var apagado = new CognitoProperties(false, " ", null, null, null, " ");
        assertThat(apagado.issuerUri()).isNull();
        assertThat(apagado.defaultRole()).isNull();
    }

    @Test
    void propiedades_inseguras_fallanAlArrancar() {
        assertThatThrownBy(() -> new CognitoProperties(true, null, null, List.of("c"), null, null))
                .hasMessageContaining("issuer-uri es obligatorio");
        assertThatThrownBy(() -> new CognitoProperties(true, "http://cognito.example", null, List.of("c"), null, null))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> new CognitoProperties(true, POOL + "/", null, List.of("c"), null, null))
                .hasMessageContaining("no debe terminar");
        assertThatThrownBy(() -> new CognitoProperties(true, POOL, "https://x.example/jwks?a=1", List.of("c"), null, null))
                .hasMessageContaining("query");
        assertThatThrownBy(() -> new CognitoProperties(true, POOL, "::no url", List.of("c"), null, null))
                .hasMessageContaining("URL valida");
        assertThatThrownBy(() -> new CognitoProperties(true, POOL, null, List.of(" "), null, null))
                .hasMessageContaining("client-ids");
        assertThatThrownBy(() -> props(List.of(), null)).hasMessageContaining("allowed-groups");
        assertThatThrownBy(() -> props(List.of("CUSTOMER"), "ADMIN")).hasMessageContaining("default-role");
    }

    @Test
    void autoridades_grupoPermitido_rolPorDefecto_eIgnoraRolesInyectados() {
        var converter = new CognitoAuthoritiesConverter(props(null, null));
        assertThat(nombres(converter.convert(jwt(List.of("CUSTOMER", "ADMIN")))))
                .containsExactlyInAnyOrder("SCOPE_Checkout.Create", "ROLE_CUSTOMER");
        assertThat(nombres(converter.convert(jwt(null))))
                .containsExactlyInAnyOrder("SCOPE_Checkout.Create", "ROLE_CUSTOMER");
        assertThat(nombres(converter.convert(jwtConClaim("cognito:groups", "CUSTOMER"))))
                .contains("ROLE_CUSTOMER");

        var sinDefecto = new CognitoAuthoritiesConverter(props(List.of("CUSTOMER"), ""));
        assertThat(nombres(sinDefecto.convert(jwt(List.of("ADMIN"))))).containsExactly("SCOPE_Checkout.Create");
        assertThatThrownBy(() -> converter.convert(jwtConClaim("cognito:groups", List.of(1))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void identidad_cognito_usaElPoolYElSub_yEntraSigueIgual() {
        var cognito = EntraIdentity.from(jwt(null));
        assertThat(cognito.objectId()).isEqualTo(UUID.fromString(SUB));
        assertThat(cognito.tenantId()).isEqualTo(EntraIdentity.cognitoTenant(POOL))
                .isEqualTo(EntraIdentity.cognitoTenant(POOL));
        assertThat(EntraIdentity.cognitoTenant(POOL + "x")).isNotEqualTo(cognito.tenantId());

        var entra = Jwt.withTokenValue("t").header("alg", "none")
                .claim("tid", "11111111-1111-1111-1111-111111111111")
                .claim("oid", "22222222-2222-2222-2222-222222222222").build();
        assertThat(EntraIdentity.from(entra).objectId()).isEqualTo(UUID.fromString("22222222-2222-2222-2222-222222222222"));

        var sinIss = Jwt.withTokenValue("t").header("alg", "none").claim("token_use", "access").subject(SUB).build();
        assertThatThrownBy(() -> EntraIdentity.from(sinIss)).isInstanceOf(InvalidBearerTokenException.class);
        assertThat(EntraIdentity.esUuidCanonico("no")).isFalse();
        assertThat(EntraIdentity.esUuidCanonico("1-1-1-1-1")).isFalse();
    }

    @Test
    void resolver_rechazaIssuersIguales() {
        assertThatThrownBy(() -> SecurityConfig.issuerResolver(POOL, null, null, props(null, null)))
                .hasMessageContaining("no puede coincidir");
    }

    private static List<String> nombres(java.util.Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt jwt(List<String> groups) {
        return jwtConClaim("cognito:groups", groups);
    }

    private static Jwt jwtConClaim(String name, Object value) {
        var builder = Jwt.withTokenValue("t").header("alg", "RS256").issuer(POOL).subject(SUB)
                .claim("token_use", "access").claim("roles", List.of("ADMIN")).claim("scp", "Orders.Read")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"));
        if (value != null) builder.claim(name, value);
        return builder.build();
    }
}
