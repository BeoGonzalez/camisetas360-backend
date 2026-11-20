package com.camisetas360.kafkaui.gate;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Solo las cuentas ADMIN entran a UI for Apache Kafka.
 *
 * <p>El RBAC propio de la UI deja iniciar sesion a cualquier cuenta del proveedor (solo le oculta los
 * clusters). Este filtro corre despues de Spring Security y responde 403 a toda sesion OAuth2 cuyo token
 * no traiga el rol exigido en el claim de su proveedor: {@code cognito:groups} para Cognito y
 * {@code roles} (app roles) para Entra ID. Un proveedor sin claim configurado se rechaza siempre.
 *
 * <p>Las peticiones sin sesion siguen su curso: Spring Security las redirige al login. Quedan fuera del
 * filtro el login, el callback OAuth2, el logout (para poder cambiar de cuenta) y el health check del NLB.
 */
public class AdminOnlyWebFilter implements WebFilter, Ordered {

    /** Despues de WebFilterChainProxy (-100): la sesion ya esta resuelta en el exchange. */
    static final int ORDER = -50;

    private static final Logger log = LoggerFactory.getLogger(AdminOnlyWebFilter.class);
    private static final List<String> PUBLIC_PREFIXES =
            List.of("/actuator/health", "/login", "/oauth2/", "/logout");

    private final String requiredRole;
    private final Map<String, String> roleClaimByRegistration;
    private final String contextPath;

    /**
     * @param requiredRole            rol exigido (ADMIN)
     * @param roleClaimByRegistration id de registro OAuth2 de la UI ({@code cognito}, {@code azure}) -> claim con
     *                                los roles en el ID token
     * @param contextPath             {@code server.servlet.context-path} de la UI ({@code /kafka-ui}), o vacio
     */
    public AdminOnlyWebFilter(String requiredRole, Map<String, String> roleClaimByRegistration, String contextPath) {
        this.requiredRole = requiredRole;
        this.roleClaimByRegistration = Map.copyOf(roleClaimByRegistration);
        this.contextPath = normalize(contextPath);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isPublic(path(exchange))) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal()
                .map(principal -> !(principal instanceof OAuth2AuthenticationToken token) || isAdmin(token))
                .defaultIfEmpty(true)
                .flatMap(allowed -> allowed ? chain.filter(exchange) : forbid(exchange));
    }

    boolean isAdmin(OAuth2AuthenticationToken token) {
        String claim = roleClaimByRegistration.get(token.getAuthorizedClientRegistrationId());
        if (claim == null) {
            return false;
        }
        Object value = token.getPrincipal().getAttributes().get(claim);
        if (value instanceof Collection<?> roles) {
            return roles.stream().anyMatch(requiredRole::equals);
        }
        return requiredRole.equals(value);
    }

    private String path(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (!contextPath.isEmpty() && (path.equals(contextPath) || path.startsWith(contextPath + "/"))) {
            path = path.substring(contextPath.length());
        }
        return path.isEmpty() ? "/" : path;
    }

    private static boolean isPublic(String path) {
        return PUBLIC_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(
                prefix.endsWith("/") ? prefix : prefix + "/"));
    }

    private Mono<Void> forbid(ServerWebExchange exchange) {
        log.info("Acceso denegado a Kafka UI: la cuenta no tiene el rol {}", requiredRole);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        boolean html = exchange.getRequest().getHeaders().getAccept().stream()
                .anyMatch(type -> type.isCompatibleWith(MediaType.TEXT_HTML) && !type.isWildcardType());
        String body;
        if (html) {
            response.getHeaders().setContentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8));
            body = """
                    <!doctype html><html lang="es"><head><meta charset="utf-8"><title>Acceso denegado</title></head>
                    <body style="font-family:sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem">
                    <h1>Acceso denegado</h1>
                    <p>Kafka UI es solo para cuentas con el rol <strong>%s</strong>.</p>
                    <form method="post" action="%s/logout"><button type="submit">Cerrar sesion</button></form>
                    </body></html>
                    """.formatted(requiredRole, contextPath);
        } else {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            body = "{\"error\":\"forbidden\",\"message\":\"Kafka UI es solo para cuentas " + requiredRole + "\"}";
        }
        var buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static String normalize(String contextPath) {
        if (contextPath == null || contextPath.isBlank() || contextPath.equals("/")) {
            return "";
        }
        String trimmed = contextPath.trim();
        trimmed = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
