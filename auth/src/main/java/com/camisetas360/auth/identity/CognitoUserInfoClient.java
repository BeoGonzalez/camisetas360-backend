package com.camisetas360.auth.identity;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Fallback best-effort al endpoint {@code /oauth2/userInfo} de Cognito para email/name.
 * Timeout corto, sin redirecciones (el bearer no sale del host configurado) y nunca propaga errores:
 * {@code /profile} debe responder aunque Cognito no este disponible.
 */
public class CognitoUserInfoClient {
    private static final Logger log = LoggerFactory.getLogger(CognitoUserInfoClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() { };

    public record UserInfo(String email, String name) {
        static final UserInfo EMPTY = new UserInfo(null, null);
    }

    private final RestClient client;
    private final URI uri;

    public CognitoUserInfoClient(String userInfoUri, Duration timeout) {
        this.uri = URI.create(userInfoUri);
        var http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(requestFactory).build();
    }

    public UserInfo fetch(String accessToken) {
        try {
            Map<String, Object> body = client.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(JSON_OBJECT);
            return body == null ? UserInfo.EMPTY
                    : new UserInfo(CognitoClaimsMapper.text(body.get("email")), CognitoClaimsMapper.text(body.get("name")));
        } catch (RuntimeException exception) {
            // Nunca se registra el token ni el cuerpo de la respuesta.
            log.warn("Cognito userInfo no disponible ({}); el perfil se guarda sin email/name nuevos",
                    exception.getClass().getSimpleName());
            return UserInfo.EMPTY;
        }
    }
}
