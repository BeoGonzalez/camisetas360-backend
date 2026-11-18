package com.camisetas360.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.camisetas360.catalog.messaging.UsuarioAnalitico;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

class PublicCatalogBearerTokenResolverTest {

    private static final String ENTRA = "https://login.microsoftonline.com/t/v2.0";
    private static final String COGNITO = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_X";
    private static final String SUB = "33333333-3333-3333-3333-333333333333";

    private static String token(String issuer) {
        return new PlainJWT(new PlainHeader(), new JWTClaimsSet.Builder().issuer(issuer).build()).serialize();
    }

    private static MockHttpServletRequest get(String path, String token) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    void lecturaPublica_conservaTokensDeIssuersDeConfianza_eIgnoraElResto() {
        var resolver = new PublicCatalogBearerTokenResolver(Set.of(ENTRA, COGNITO));
        String detalle = "/api/v1/catalog/products/SA-INT-LOC-2627";
        assertThat(resolver.resolve(get(detalle, token(COGNITO)))).isEqualTo(token(COGNITO));
        assertThat(resolver.resolve(get(detalle, token(ENTRA)))).isEqualTo(token(ENTRA));
        assertThat(resolver.resolve(get(detalle, token("https://otro.example")))).isNull();
        assertThat(resolver.resolve(get(detalle, "no-es-jwt"))).isNull();
    }

    @Test
    void soloEntra_ignoraCognito_yLasEscriturasSiempreUsanElToken() {
        var resolver = new PublicCatalogBearerTokenResolver(ENTRA);
        assertThat(resolver.resolve(get("/api/v1/catalog/products/A", token(COGNITO)))).isNull();
        var escritura = get("/api/v1/catalog/products/A", token("https://otro.example"));
        escritura.setMethod("PUT");
        assertThat(resolver.resolve(escritura)).isEqualTo(token("https://otro.example"));
        assertThat(resolver.resolve(new MockHttpServletRequest("GET", "/api/v1/catalog/products"))).isNull();
    }

    @Test
    void usuarioAnalitico_cognitoUsaElMismoIdQueCarrito_entraUsaTidOid() {
        Jwt cognito = Jwt.withTokenValue("t").header("alg", "RS256").issuer(COGNITO).subject(SUB)
                .claim("token_use", "access").build();
        String tenant = UUID.nameUUIDFromBytes(("cognito:" + COGNITO).getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(UsuarioAnalitico.desde(cognito)).isEqualTo(tenant + ":" + SUB);

        Jwt entra = Jwt.withTokenValue("t").header("alg", "RS256").issuer(ENTRA)
                .claim("tid", "AAAAAAAA-1111-1111-1111-111111111111").claim("oid", "22222222-2222-2222-2222-222222222222")
                .build();
        assertThat(UsuarioAnalitico.desde(entra))
                .isEqualTo("aaaaaaaa-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222");

        Jwt otro = Jwt.withTokenValue("t").header("alg", "RS256").subject("x").build();
        assertThat(UsuarioAnalitico.desde(otro)).isEqualTo("sub:x");
    }
}
