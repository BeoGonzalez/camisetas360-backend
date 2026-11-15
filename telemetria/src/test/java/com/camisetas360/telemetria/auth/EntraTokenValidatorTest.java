package com.camisetas360.telemetria.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntraTokenValidatorTest {

    static final String ISSUER = "https://login.microsoftonline.com/11111111-1111-1111-1111-111111111111/v2.0";
    static final String AUDIENCE = "api://camisetas360";
    static final String TID = "11111111-1111-1111-1111-111111111111";
    static final String OID = "22222222-2222-2222-2222-222222222222";

    static RSAKey clave;
    static EntraTokenValidator validator;

    @BeforeAll
    static void setUp() throws JOSEException {
        clave = new RSAKeyGenerator(2048).keyID("k1").generate();
        validator = new EntraTokenValidator(ISSUER, AUDIENCE, new ImmutableJWKSet<>(new JWKSet(clave.toPublicJWK())));
    }

    static JWTClaimsSet.Builder claimsValidos() {
        Instant ahora = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(600)))
                .claim("tid", TID)
                .claim("oid", OID)
                .claim("roles", List.of("ADMIN"))
                .claim("scp", "Catalog.Read Orders.Read");
    }

    static String firmar(JWTClaimsSet claims) throws JOSEException {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(clave));
        return jwt.serialize();
    }

    static String token(UnaryOperator<JWTClaimsSet.Builder> cambio) throws JOSEException {
        return firmar(cambio.apply(claimsValidos()).build());
    }

    @Test
    void tokenValido_devuelveUsuarioTidOid() throws Exception {
        assertThat(validator.validar(token(b -> b))).isEqualTo(TID + ":" + OID);
    }

    @Test
    void identidadEnMayusculas_seNormalizaAMinusculas() throws Exception {
        String t = token(b -> b.claim("tid", TID.toUpperCase()).claim("oid", OID.toUpperCase()));
        assertThat(validator.validar(t)).isEqualTo(TID + ":" + OID);
    }

    @Test
    void catalogManagerConScopeComoLista_esValido() throws Exception {
        String t = token(b -> b.claim("roles", List.of("CATALOG_MANAGER")).claim("scp", List.of("Catalog.Read")));
        assertThat(validator.validar(t)).isEqualTo(TID + ":" + OID);
    }

    @Test
    void sinToken_seRechaza() {
        assertThatThrownBy(() -> validator.validar(null)).isInstanceOf(TokenInvalidoException.class);
        assertThatThrownBy(() -> validator.validar(" ")).isInstanceOf(TokenInvalidoException.class);
        assertThatThrownBy(() -> validator.validar("no-es-un-jwt")).isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    void otroIssuer_otraAudienceOVencido_seRechazan() {
        assertRechazado(b -> b.issuer("https://login.microsoftonline.com/otro/v2.0"));
        assertRechazado(b -> b.audience("api://otra"));
        assertRechazado(b -> b.expirationTime(Date.from(Instant.now().minusSeconds(3600))));
        assertRechazado(b -> b.expirationTime(null));
    }

    @Test
    void sinRolOSinScope_seRechaza() {
        assertRechazado(b -> b.claim("roles", List.of("CUSTOMER")));
        assertRechazado(b -> b.claim("roles", null));
        assertRechazado(b -> b.claim("scp", "Orders.Read"));
        assertRechazado(b -> b.claim("roles", "ADMIN")); // formato invalido: debe ser lista
    }

    @Test
    void identidadNoCanonica_seRechaza() {
        assertRechazado(b -> b.claim("oid", "1-1-1-1-1")); // UUID.fromString lo acepta, pero no es canonico
        assertRechazado(b -> b.claim("tid", "no-uuid"));
        assertRechazado(b -> b.claim("oid", null));
    }

    @Test
    void firmaDeOtraClave_hmacONone_seRechazan() throws Exception {
        RSAKey otra = new RSAKeyGenerator(2048).keyID("k1").generate();
        var ajeno = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claimsValidos().build());
        ajeno.sign(new RSASSASigner(otra));
        assertThatThrownBy(() -> validator.validar(ajeno.serialize())).isInstanceOf(TokenInvalidoException.class);

        var hmac = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsValidos().build());
        hmac.sign(new MACSigner(new byte[32]));
        assertThatThrownBy(() -> validator.validar(hmac.serialize())).isInstanceOf(TokenInvalidoException.class);

        String none = new PlainJWT(claimsValidos().build()).serialize();
        assertThatThrownBy(() -> validator.validar(none)).isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    void configuracionIncompleta_fallaAlCrear() {
        assertThatThrownBy(() -> EntraTokenValidator.remoto("", AUDIENCE, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ENTRA_ISSUER_URI");
        assertThatThrownBy(() -> EntraTokenValidator.remoto(ISSUER, null, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ENTRA_AUDIENCE");
    }

    private static void assertRechazado(UnaryOperator<JWTClaimsSet.Builder> cambio) {
        assertThatThrownBy(() -> validator.validar(token(cambio))).isInstanceOf(TokenInvalidoException.class);
    }
}
