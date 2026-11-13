package com.camisetas360.auth.config;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import static org.assertj.core.api.Assertions.*;

class CognitoPropertiesTest {

    private static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_AbCdEf123";

    private static CognitoProperties enabled(String issuer, List<String> clients, String resourceServer,
            List<String> groups, String defaultRole, String userInfo, Duration timeout) {
        return new CognitoProperties(true, issuer, null, clients, resourceServer, groups, defaultRole, userInfo, timeout);
    }

    private static CognitoProperties valid() {
        return enabled(ISSUER, List.of("client"), "camisetas360-api", null, null, null, null);
    }

    @Test
    void disabledNeedsNothingAndHasSafeDefaults() {
        var properties = new CognitoProperties(false, " ", null, null, "", null, null, null, null);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.issuerUri()).isNull();
        assertThat(properties.userPoolId()).isNull();
        assertThat(properties.clientIds()).isEmpty();
        assertThat(properties.allowedGroups()).containsExactly("CUSTOMER");
        assertThat(properties.defaultRole()).isEqualTo("CUSTOMER");
        assertThat(properties.userInfoTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void enabledDerivesJwksPoolIdAndScopePrefix() {
        var properties = valid();
        assertThat(properties.jwkSetUri()).isEqualTo(ISSUER + "/.well-known/jwks.json");
        assertThat(properties.userPoolId()).isEqualTo("us-east-1_AbCdEf123");
        assertThat(properties.scopePrefix()).isEqualTo("camisetas360-api/");
    }

    @Test
    void explicitJwksAndCleanListsAreKept() {
        var properties = new CognitoProperties(true, ISSUER, "https://keys.example.test/jwks.json",
                List.of(" client ", "", "client"), "api", List.of("CUSTOMER", " ", "CATALOG_MANAGER"), " CUSTOMER ",
                "https://pool.auth.us-east-1.amazoncognito.com/oauth2/userInfo", Duration.ofMillis(500));
        assertThat(properties.jwkSetUri()).isEqualTo("https://keys.example.test/jwks.json");
        assertThat(properties.clientIds()).containsExactly("client");
        assertThat(properties.allowedGroups()).containsExactly("CUSTOMER", "CATALOG_MANAGER");
        assertThat(properties.defaultRole()).isEqualTo("CUSTOMER");
    }

    @Test
    void adminIsAllowedOnlyWhenListedExplicitly() {
        var properties = enabled(ISSUER, List.of("c"), "api", List.of("CUSTOMER", "ADMIN"), "CUSTOMER", null, null);
        assertThat(properties.allowedGroups()).contains("ADMIN");
    }

    @Test
    void missingIssuerFailsWithAClearMessage() {
        assertThatThrownBy(() -> enabled(null, List.of("c"), "api", null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("app.security.cognito.issuer-uri es obligatorio cuando enabled=true (COGNITO_ISSUER_URI)");
    }

    @Test
    void missingClientIdsFailsWithAClearMessage() {
        assertThatThrownBy(() -> enabled(ISSUER, List.of(" "), "api", null, null, null, null))
                .hasMessage("app.security.cognito.client-ids es obligatorio cuando enabled=true (COGNITO_CLIENT_IDS)");
    }

    @Test
    void missingResourceServerFailsWithAClearMessage() {
        assertThatThrownBy(() -> enabled(ISSUER, List.of("c"), null, null, null, null, null))
                .hasMessageContaining("resource-server-id es obligatorio");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://cognito-idp.us-east-1.amazonaws.com/us-east-1_x",   // sin TLS fuera de loopback
            ISSUER + "/",                                                // '/' final
            "https://cognito-idp.us-east-1.amazonaws.com",               // sin user pool
            ISSUER + "?x=1",                                             // query
            "https://user:pass@cognito-idp.us-east-1.amazonaws.com/p",   // credenciales
            "ht tp://bad",                                               // URL invalida
            "file:///etc/passwd"})
    void unsafeOrInvalidIssuerFailsStartup(String issuer) {
        assertThatThrownBy(() -> enabled(issuer, List.of("c"), "api", null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("app.security.cognito.");
    }

    @Test
    void loopbackHttpIsAcceptedForLocalTests() {
        assertThatCode(() -> enabled("http://localhost:9000/pool", List.of("c"), "api", null, null,
                "http://[::1]:9000/oauth2/userInfo", null)).doesNotThrowAnyException();
    }

    @Test
    void invalidRolePolicyFailsStartup() {
        assertThatThrownBy(() -> enabled(ISSUER, List.of("c"), "api", List.of("CUSTOMER"), "CATALOG_MANAGER", null, null))
                .hasMessageContaining("default-role debe pertenecer a allowed-groups");
        assertThatThrownBy(() -> enabled(ISSUER, List.of("c"), "api", List.of("ADMIN", "CUSTOMER"), "ADMIN", null, null))
                .hasMessageContaining("default-role no puede ser ADMIN");
        assertThatThrownBy(() -> enabled(ISSUER, List.of("c"), "api", List.of(" "), "CUSTOMER", null, null))
                .hasMessageContaining("allowed-groups no puede estar vacio");
        assertThatThrownBy(() -> enabled(ISSUER, List.of("c"), "api/", null, null, null, null))
                .hasMessageContaining("resource-server-id no debe terminar en '/'");
    }

    @Test
    void unsafeUserInfoOrTimeoutFailsStartup() {
        assertThatThrownBy(() -> enabled(ISSUER, List.of("c"), "api", null, null, "http://example.test/oauth2/userInfo", null))
                .hasMessageContaining("user-info-uri debe usar https");
        assertThatThrownBy(() -> enabled(ISSUER, List.of("c"), "api", null, null, null, Duration.ZERO))
                .hasMessageContaining("user-info-timeout");
        assertThatThrownBy(() -> enabled(ISSUER, List.of("c"), "api", null, null, null, Duration.ofSeconds(11)))
                .hasMessageContaining("user-info-timeout");
    }

    // ---- Arranque real del contexto con la configuracion de seguridad multi-issuer ----

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EntraConverter.class, IssuerAuthenticationConfig.class)
            .withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=https://login.example.test/tenant/v2.0",
                    "spring.security.oauth2.resourceserver.jwt.audiences[0]=api");

    @Test
    void enabledWithoutConfigurationFailsStartupWithAClearMessage() {
        runner.withPropertyValues("app.security.cognito.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessage("app.security.cognito.issuer-uri es obligatorio cuando enabled=true (COGNITO_ISSUER_URI)");
        });
    }

    @Test
    void cognitoIssuerEqualToEntraIssuerFailsStartup() {
        runner.withPropertyValues("app.security.cognito.enabled=true",
                "app.security.cognito.issuer-uri=https://login.example.test/tenant/v2.0",
                "app.security.cognito.client-ids=c", "app.security.cognito.resource-server-id=api").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("no puede coincidir con el issuer de Entra ID");
        });
    }

    @Test
    void enabledStartsWithoutContactingCognito() {
        // Issuer inalcanzable: el JWKS es perezoso, el arranque no depende de Cognito.
        runner.withPropertyValues("app.security.cognito.enabled=true",
                "app.security.cognito.issuer-uri=http://127.0.0.1:1/us-east-1_Offline",
                "app.security.cognito.client-ids=c,d", "app.security.cognito.resource-server-id=api").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TrustedIssuers.class).issuers())
                    .containsExactly("https://login.example.test/tenant/v2.0", "http://127.0.0.1:1/us-east-1_Offline");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CognitoProperties.class)
    static class EntraConverter {
        @Bean JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new JwtAuthenticationConverter();
        }
    }
}
