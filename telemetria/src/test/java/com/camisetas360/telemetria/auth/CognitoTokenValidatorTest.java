package com.camisetas360.telemetria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CognitoTokenValidatorTest {

    static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Pool";
    static final String SUB = "74488428-c0f1-705b-cc92-01de52ce490d";

    static RSAKey clave;
    static CognitoTokenValidator validator;

    @BeforeAll
    static void setUp() throws JOSEException {
        clave = new RSAKeyGenerator(2048).keyID("k1").generate();
        validator = new CognitoTokenValidator(ISSUER, List.of("web"), new ImmutableJWKSet<>(new JWKSet(clave.toPublicJWK())));
    }

    static String token(UnaryOperator<JWTClaimsSet.Builder> cambios) throws JOSEException {
        Instant ahora = Instant.now();
        var claims = cambios.apply(new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(SUB)
                .issueTime(Date.from(ahora)).expirationTime(Date.from(ahora.plusSeconds(600)))
                .claim("token_use", "access").claim("client_id", "web").claim("username", SUB)
                .claim("cognito:groups", List.of("ADMIN"))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(clave));
        return jwt.serialize();
    }

    @Test
    void tokenDeAdminDevuelveElUsuarioComoEnElBackend() throws Exception {
        String tenant = UUID.nameUUIDFromBytes(("cognito:" + ISSUER).getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(validator.validar(token(b -> b))).isEqualTo(tenant + ":" + SUB);
        assertThat(validator.validar(token(b -> b.claim("cognito:groups", "CATALOG_MANAGER")))).endsWith(SUB);
        assertThat(validator.issuer()).isEqualTo(ISSUER);
    }

    @Test
    void rechazaTokensQueNoCumplenLasReglas() {
        Map<String, UnaryOperator<JWTClaimsSet.Builder>> invalidos = Map.of(
                "id token", b -> b.claim("token_use", "id"),
                "otro client", b -> b.claim("client_id", "kafka-ui"),
                "otro issuer", b -> b.issuer(ISSUER + "x"),
                "expirado", b -> b.expirationTime(Date.from(Instant.now().minusSeconds(120))),
                "cliente", b -> b.claim("cognito:groups", List.of("CUSTOMER")),
                "sin grupos", b -> b.claim("cognito:groups", null),
                "grupos raros", b -> b.claim("cognito:groups", 7),
                "sub no uuid", b -> b.subject("no-uuid"),
                "m2m", b -> b.claim("username", null));
        invalidos.forEach((caso, cambio) -> assertThatThrownBy(() -> validator.validar(token(cambio)))
                .as(caso).isInstanceOf(TokenInvalidoException.class));
        assertThatThrownBy(() -> validator.validar(" ")).isInstanceOf(TokenInvalidoException.class);
        assertThatThrownBy(() -> validator.validar("no.es.jwt")).isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    void configuracionInvalida() {
        var claves = new ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext>(new JWKSet());
        assertThatThrownBy(() -> new CognitoTokenValidator("", List.of("web"), claves)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CognitoTokenValidator(ISSUER + "/", List.of("web"), claves)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CognitoTokenValidator(ISSUER, List.of(), claves)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CognitoTokenValidator.remoto("nota url", List.of("web"))).isInstanceOf(IllegalStateException.class);
        assertThat(CognitoTokenValidator.remoto(ISSUER, List.of("web")).issuer()).isEqualTo(ISSUER);
    }
}
