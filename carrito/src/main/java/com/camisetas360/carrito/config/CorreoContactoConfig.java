package com.camisetas360.carrito.config;

import com.camisetas360.carrito.client.CognitoCorreoContacto;
import com.camisetas360.carrito.security.CognitoProperties;
import com.camisetas360.carrito.service.CorreoContacto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Los access tokens de Cognito no traen email: el checkout lo consulta a Cognito para poder notificar
 * la compra. Con Cognito apagado no hay consulta (Entra ID trae email/preferred_username en el token).
 */
@Configuration(proxyBeanMethods = false)
public class CorreoContactoConfig {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Bean
    public CorreoContacto correoContacto(CognitoProperties cognito) {
        if (!cognito.enabled()) {
            return CorreoContacto.NINGUNO;
        }
        // Sin redirecciones: el access token solo viaja al host del issuer configurado.
        var http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(TIMEOUT);
        var rest = RestClient.builder()
                .baseUrl(CognitoCorreoContacto.endpoint(cognito.issuerUri()))
                .requestFactory(requestFactory)
                .build();
        return new CognitoCorreoContacto(rest, cognito.issuerUri());
    }
}
