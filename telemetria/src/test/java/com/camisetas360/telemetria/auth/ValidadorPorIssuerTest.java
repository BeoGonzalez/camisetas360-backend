package com.camisetas360.telemetria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidadorPorIssuerTest {

    final ValidadorToken entra = mock(ValidadorToken.class);
    final ValidadorToken cognito = mock(ValidadorToken.class);
    final ValidadorPorIssuer validador = new ValidadorPorIssuer(Map.of("https://entra", entra, "https://cognito", cognito));

    static String token(String issuer) {
        return new PlainJWT(new JWTClaimsSet.Builder().issuer(issuer).build()).serialize();
    }

    @Test
    void enrutaPorIssuerExacto() throws Exception {
        String tokenCognito = token("https://cognito");
        when(cognito.validar(tokenCognito)).thenReturn("t:o");
        assertThat(validador.validar(tokenCognito)).isEqualTo("t:o");
    }

    @Test
    void rechazaIssuerDesconocidoSinIssuerOTokenMalformado() {
        assertThatThrownBy(() -> validador.validar(token("https://otro"))).isInstanceOf(TokenInvalidoException.class);
        assertThatThrownBy(() -> validador.validar(token(null))).isInstanceOf(TokenInvalidoException.class);
        assertThatThrownBy(() -> validador.validar("basura")).isInstanceOf(TokenInvalidoException.class);
        assertThatThrownBy(() -> validador.validar(null)).isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    void exigeAlMenosUnIssuer() {
        assertThatThrownBy(() -> new ValidadorPorIssuer(Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
