package com.camisetas360.kafkaui.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class AdminOnlyWebFilterTest {

    private static final HttpStatusCode FORBIDDEN = HttpStatusCode.valueOf(403);

    private final AdminOnlyWebFilter filter = new AdminOnlyWebFilter("ADMIN",
            Map.of("cognito", "cognito:groups", "azure", "roles"), "/kafka-ui");

    @Test
    void cognitoAdminEntra() {
        assertThat(run("/kafka-ui/api/clusters", cognito(List.of("ADMIN")))).isNull();
    }

    @Test
    void cognitoAdminConOtrosGruposEntra() {
        assertThat(run("/kafka-ui/", cognito(List.of("CUSTOMER", "ADMIN")))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "CATALOG_MANAGER", "admin"})
    void cognitoSinAdminRecibe403(String grupo) {
        assertThat(run("/kafka-ui/api/clusters", cognito(List.of(grupo)))).isEqualTo(FORBIDDEN);
    }

    @Test
    void cognitoSinGruposRecibe403() {
        assertThat(run("/kafka-ui/", oauth("cognito", Map.of()))).isEqualTo(FORBIDDEN);
    }

    @Test
    void entraConAppRoleAdminEntra() {
        assertThat(run("/kafka-ui/api/clusters", oauth("azure", Map.of("roles", List.of("ADMIN"))))).isNull();
    }

    @Test
    void entraConClaimDeTextoAdminEntra() {
        assertThat(run("/kafka-ui/", oauth("azure", Map.of("roles", "ADMIN")))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "CATALOG_MANAGER"})
    void entraSinAdminRecibe403(String rol) {
        assertThat(run("/kafka-ui/", oauth("azure", Map.of("roles", List.of(rol))))).isEqualTo(FORBIDDEN);
    }

    @Test
    void entraNoUsaElClaimDeCognito() {
        assertThat(run("/kafka-ui/", oauth("azure", Map.of("cognito:groups", List.of("ADMIN")))))
                .isEqualTo(FORBIDDEN);
    }

    @Test
    void proveedorDesconocidoRecibe403() {
        assertThat(run("/kafka-ui/", oauth("github", Map.of("roles", List.of("ADMIN"))))).isEqualTo(FORBIDDEN);
    }

    @Test
    void sinSesionSigueAlLoginDeSpringSecurity() {
        assertThat(run("/kafka-ui/api/clusters", null)).isNull();
    }

    @Test
    void otroTipoDeAutenticacionNoSeFiltra() {
        var basic = new UsernamePasswordAuthenticationToken("u", "p", AuthorityUtils.NO_AUTHORITIES);
        assertThat(run("/kafka-ui/", basic)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/kafka-ui/actuator/health", "/kafka-ui/actuator/health/readiness", "/kafka-ui/login",
            "/kafka-ui/oauth2/authorization/cognito", "/kafka-ui/login/oauth2/code/cognito", "/kafka-ui/logout"})
    void rutasDeLoginLogoutYHealthNoSeFiltran(String path) {
        assertThat(run(path, cognito(List.of("CUSTOMER")))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/kafka-ui/loginx", "/kafka-ui/actuator/healthy", "/kafka-ui/api/logout-ish"})
    void prefijosParecidosSiSeFiltran(String path) {
        assertThat(run(path, cognito(List.of("CUSTOMER")))).isEqualTo(FORBIDDEN);
    }

    @Test
    void respuestaHtmlConFormularioDeLogout() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/kafka-ui/")
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8"));
        filter.filter(withPrincipal(exchange, cognito(List.of("CUSTOMER"))), e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("Acceso denegado", "action=\"/kafka-ui/logout\"", "ADMIN");
    }

    @Test
    void respuestaJsonParaLaApi() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/kafka-ui/api/clusters")
                .header("Accept", "application/json"));
        filter.filter(withPrincipal(exchange, cognito(List.of("CUSTOMER"))), e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"error\":\"forbidden\"");
    }

    @Test
    void sinContextPathFiltraLaRaiz() {
        var raiz = new AdminOnlyWebFilter("ADMIN", Map.of("cognito", "cognito:groups"), "/");
        assertThat(run(raiz, "/", cognito(List.of("CUSTOMER")))).isEqualTo(FORBIDDEN);
        assertThat(run(raiz, "/logout", cognito(List.of("CUSTOMER")))).isNull();
        var nulo = new AdminOnlyWebFilter("ADMIN", Map.of("cognito", "cognito:groups"), null);
        assertThat(run(nulo, "/actuator/health", cognito(List.of("CUSTOMER")))).isNull();
    }

    @Test
    void contextPathSinBarrasSeNormaliza() {
        var f = new AdminOnlyWebFilter("ADMIN", Map.of("cognito", "cognito:groups"), "kafka-ui/");
        assertThat(run(f, "/kafka-ui", cognito(List.of("CUSTOMER")))).isEqualTo(FORBIDDEN);
        assertThat(run(f, "/kafka-ui/logout", cognito(List.of("CUSTOMER")))).isNull();
    }

    @Test
    void correDespuesDeSpringSecurity() {
        assertThat(filter.getOrder()).isGreaterThan(-100);
    }

    /** @return el estado si el filtro corto la cadena, o null si la dejo seguir. */
    private HttpStatusCode run(String path, Principal principal) {
        return run(filter, path, principal);
    }

    private static HttpStatusCode run(AdminOnlyWebFilter f, String path, Principal principal) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        var siguio = new AtomicBoolean();
        WebFilterChain chain = e -> {
            siguio.set(true);
            return Mono.empty();
        };
        f.filter(principal == null ? exchange : withPrincipal(exchange, principal), chain).block();
        return siguio.get() ? null : exchange.getResponse().getStatusCode();
    }

    private static ServerWebExchange withPrincipal(ServerWebExchange exchange, Principal principal) {
        return exchange.mutate().principal(Mono.just(principal)).build();
    }

    private static OAuth2AuthenticationToken cognito(List<String> grupos) {
        return oauth("cognito", Map.of("cognito:groups", grupos));
    }

    private static OAuth2AuthenticationToken oauth(String registration, Map<String, Object> claims) {
        var builder = OidcIdToken.withTokenValue("id-token").subject("sub-1").claim("email", "u@example.test")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        var user = new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), builder.build());
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), registration);
    }
}
