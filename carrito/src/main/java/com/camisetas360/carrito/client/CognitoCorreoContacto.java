package com.camisetas360.carrito.client;

import com.camisetas360.carrito.service.CorreoContacto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Obtiene el email de un usuario de Cognito con la API {@code GetUser} del user pool. Acepta el propio
 * access token del usuario (scope {@code aws.cognito.signin.user.admin}, que traen los tokens del flujo
 * password), sin credenciales AWS ni Hosted UI. Solo se llama con tokens cuyo {@code iss} es exactamente
 * el del user pool configurado: un token de Entra nunca sale hacia AWS.
 */
public class CognitoCorreoContacto implements CorreoContacto {

    private static final Logger log = LoggerFactory.getLogger(CognitoCorreoContacto.class);
    private static final MediaType AMZ_JSON = MediaType.parseMediaType("application/x-amz-json-1.1");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;
    private final String issuer;

    /** @param rest cliente cuya base URL es el endpoint de Cognito ({@link #endpoint(String)}). */
    public CognitoCorreoContacto(RestClient rest, String issuer) {
        this.rest = rest;
        this.issuer = issuer;
    }

    /** {@code https://cognito-idp.<region>.amazonaws.com/<poolId>} -> {@code https://cognito-idp.<region>.amazonaws.com}. */
    public static String endpoint(String issuer) {
        URI uri = URI.create(issuer);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    @Override
    public Optional<String> buscar(Jwt jwt) {
        if (!issuer.equals(jwt.getClaimAsString("iss"))) {
            return Optional.empty();
        }
        try {
            String respuesta = rest.post().uri("/")
                    .header("X-Amz-Target", "AWSCognitoIdentityProviderService.GetUser")
                    .contentType(AMZ_JSON)
                    .body(JSON.writeValueAsString(Map.of("AccessToken", jwt.getTokenValue())))
                    .retrieve()
                    .body(String.class);
            return email(respuesta);
        } catch (RuntimeException error) {
            // Nunca se registra el token ni la respuesta (contiene datos personales).
            log.warn("No se pudo obtener el email del usuario desde Cognito ({})", error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Optional<String> email(String respuesta) {
        if (respuesta == null || respuesta.isBlank()) {
            return Optional.empty();
        }
        for (JsonNode atributo : JSON.readTree(respuesta).path("UserAttributes")) {
            if ("email".equals(atributo.path("Name").asString(null))) {
                return Optional.ofNullable(atributo.path("Value").asString(null)).filter(v -> !v.isBlank());
            }
        }
        return Optional.empty();
    }
}
