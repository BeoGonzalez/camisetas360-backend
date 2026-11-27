package com.camisetas360.carrito.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CognitoCorreoContactoTest {

    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Pool";
    private static final String ENDPOINT = "https://cognito-idp.us-east-1.amazonaws.com";

    private MockRestServiceServer server;
    private CognitoCorreoContacto client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(CognitoCorreoContacto.endpoint(ISSUER));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CognitoCorreoContacto(builder.build(), ISSUER);
    }

    @Test
    void endpoint_esElHostDelIssuer() {
        assertThat(CognitoCorreoContacto.endpoint(ISSUER)).isEqualTo(ENDPOINT);
        assertThat(CognitoCorreoContacto.endpoint("http://127.0.0.1:9000/pool")).isEqualTo("http://127.0.0.1:9000");
    }

    @Test
    void buscar_llamaGetUserConElAccessToken_yDevuelveElEmail() {
        server.expect(requestTo(ENDPOINT + "/")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Amz-Target", "AWSCognitoIdentityProviderService.GetUser"))
                .andExpect(header("Content-Type", "application/x-amz-json-1.1"))
                .andExpect(content().json("{\"AccessToken\":\"cognito-token\"}"))
                .andRespond(withSuccess("""
                        {"Username":"u","UserAttributes":[{"Name":"sub","Value":"x"},
                         {"Name":"email","Value":"cliente@example.test"}]}
                        """, MediaType.parseMediaType("application/x-amz-json-1.1")));

        assertThat(client.buscar(token(ISSUER))).contains("cliente@example.test");
        server.verify();
    }

    @Test
    void buscar_vacioSiElUsuarioNoTieneEmail() {
        server.expect(requestTo(ENDPOINT + "/"))
                .andRespond(withSuccess("{\"UserAttributes\":[{\"Name\":\"sub\",\"Value\":\"x\"}]}", MediaType.TEXT_PLAIN));

        assertThat(client.buscar(token(ISSUER))).isEmpty();
    }

    @Test
    void buscar_vacioSiCognitoResponde4xx() {
        server.expect(requestTo(ENDPOINT + "/")).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(client.buscar(token(ISSUER))).isEmpty();
    }

    @Test
    void buscar_vacioSiLaRespuestaEstaVacia() {
        server.expect(requestTo(ENDPOINT + "/")).andRespond(withSuccess("", MediaType.TEXT_PLAIN));

        assertThat(client.buscar(token(ISSUER))).isEmpty();
    }

    @Test
    void buscar_noEnviaTokensDeOtroIssuer() {
        assertThat(client.buscar(token("https://login.microsoftonline.com/tid/v2.0"))).isEmpty();
        server.verify();
    }

    private static Jwt token(String issuer) {
        return Jwt.withTokenValue("cognito-token").header("alg", "none").claim("iss", issuer).subject("s").build();
    }
}
