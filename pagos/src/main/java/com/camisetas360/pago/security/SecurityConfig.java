package com.camisetas360.pago.security;

import jakarta.servlet.DispatcherType;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;

/**
 * pagos es un resource server como el resto (Entra ID siempre + Amazon Cognito opcional).
 *
 * <ul>
 *   <li>Publicos: health, Swagger y los retornos de Webpay ({@code /api/v1/pago/retorno},
 *       {@code /api/v1/pago/webpay/retorno}): es el navegador del cliente volviendo desde Transbank, sin
 *       bearer token; el {@code token_ws} de la transaccion es la credencial.</li>
 *   <li>Iniciar/confirmar un pago: CUSTOMER + {@code SCOPE_Checkout.Create} (pagar es parte del checkout).</li>
 *   <li>Consultar pagos: CUSTOMER o ADMIN + {@code SCOPE_Orders.Read}.</li>
 *   <li>Alertas de fraude: solo ADMIN + {@code SCOPE_Orders.Read}.</li>
 *   <li>Todo lo demas: denegado.</li>
 * </ul>
 */
@Configuration
@Import(JwtValidationConfig.class)
@EnableConfigurationProperties(CognitoProperties.class)
public class SecurityConfig {
    static final String PAGAR = "hasRole('CUSTOMER') and hasAuthority('SCOPE_Checkout.Create')";
    static final String CONSULTAR = "hasAnyRole('CUSTOMER','ADMIN') and hasAuthority('SCOPE_Orders.Read')";
    static final String ADMINISTRAR = "hasRole('ADMIN') and hasAuthority('SCOPE_Orders.Read')";

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtAuthoritiesConverter());
        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter,
            @Value("${app.cors.enabled:false}") boolean localCors, JwtDecoder entraDecoder,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String entraIssuer,
            CognitoProperties cognito) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(config -> {
                    // Local: la configuracion CORS de WebConfig (Spring MVC). Produccion: API Gateway.
                    if (!localCors) config.disable();
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // sendError (400/404/405/415...) reenvia a /error: debe conservar el estado original.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/pago/retorno").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/pago/webpay/retorno").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/pago/webpay/retorno").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/pago/iniciar", "/api/v1/pago/confirmar",
                                "/api/v1/pago/webpay/iniciar")
                        .access(new WebExpressionAuthorizationManager(PAGAR))
                        .requestMatchers(HttpMethod.GET, "/api/v1/pago/alertas")
                        .access(new WebExpressionAuthorizationManager(ADMINISTRAR))
                        .requestMatchers(HttpMethod.GET, "/api/v1/pago/{id}", "/api/v1/pago/orden/{ordenId}")
                        .access(new WebExpressionAuthorizationManager(CONSULTAR))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> {
                    if (cognito.enabled()) {
                        resource.authenticationManagerResolver(issuerResolver(entraIssuer, entraDecoder, converter, cognito));
                    } else {
                        resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter));
                    }
                });
        return http.build();
    }

    /** Cada token se enruta por su {@code iss} exacto; un issuer desconocido se rechaza con 401. */
    static JwtIssuerAuthenticationManagerResolver issuerResolver(String entraIssuer, JwtDecoder entraDecoder,
            JwtAuthenticationConverter entraConverter, CognitoProperties cognito) {
        var managers = managers(entraIssuer, entraDecoder, entraConverter, cognito);
        return new JwtIssuerAuthenticationManagerResolver(issuer -> issuer == null ? null : managers.get(issuer));
    }

    static Map<String, AuthenticationManager> managers(String entraIssuer, JwtDecoder entraDecoder,
            JwtAuthenticationConverter entraConverter, CognitoProperties cognito) {
        if (!cognito.enabled()) {
            return Map.of(entraIssuer, manager(entraDecoder, entraConverter));
        }
        if (cognito.issuerUri().equals(entraIssuer)) {
            throw new IllegalStateException(CognitoProperties.PREFIX + "issuer-uri no puede coincidir con el issuer de Entra ID");
        }
        var cognitoConverter = new JwtAuthenticationConverter();
        cognitoConverter.setJwtGrantedAuthoritiesConverter(new CognitoAuthoritiesConverter(cognito));
        return Map.of(
                entraIssuer, manager(entraDecoder, entraConverter),
                cognito.issuerUri(), manager(CognitoJwtDecoders.create(cognito), cognitoConverter));
    }

    private static AuthenticationManager manager(JwtDecoder decoder, JwtAuthenticationConverter converter) {
        var provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return new ProviderManager(provider);
    }
}
