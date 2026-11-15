package com.camisetas360.auth.config;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import static org.assertj.core.api.Assertions.*;

class CognitoAuthoritiesConverterTest {

    private final CognitoAuthoritiesConverter converter = new CognitoAuthoritiesConverter(new CognitoProperties(true,
            "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Pool", null, List.of("client"), "camisetas360-api",
            List.of("CUSTOMER", "CATALOG_MANAGER"), "CUSTOMER", null, null));

    private List<String> authorities(Map<String, Object> claims) {
        var jwt = Jwt.withTokenValue("t").header("alg", "none").subject("s").claims(c -> c.putAll(claims)).build();
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void prefixedScopesBecomeUnprefixedAuthorities() {
        assertThat(authorities(Map.of("scope", "openid email camisetas360-api/Profile.Read camisetas360-api/Orders.Read")))
                .containsExactly("SCOPE_Profile.Read", "SCOPE_Orders.Read", "ROLE_CUSTOMER");
    }

    @Test
    void scopesAsListAndDuplicatesAreHandled() {
        assertThat(authorities(Map.of("scope", List.of("camisetas360-api/Profile.Read", "camisetas360-api/Profile.Read"),
                "cognito:groups", List.of("CUSTOMER", "CUSTOMER"))))
                .containsExactly("SCOPE_Profile.Read", "ROLE_CUSTOMER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Profile.Read", "camisetas360-api/", "camisetas360-api", "otra-api/Profile.Read",
            "xcamisetas360-api/Profile.Read", "   "})
    void foreignOrEmptyScopesAreIgnored(String scope) {
        assertThat(authorities(Map.of("scope", scope))).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void groupsAreFilteredCaseSensitively() {
        assertThat(authorities(Map.of("cognito:groups", List.of("customer", "ADMIN", "Catalog_Manager"))))
                .containsExactly("ROLE_CUSTOMER");
        assertThat(authorities(Map.of("cognito:groups", "CATALOG_MANAGER"))).containsExactly("ROLE_CATALOG_MANAGER");
        assertThat(authorities(Map.of("cognito:groups", List.of("CATALOG_MANAGER", "CUSTOMER"))))
                .containsExactly("ROLE_CATALOG_MANAGER", "ROLE_CUSTOMER");
    }

    @Test
    void entraClaimsAreIgnoredForCognito() {
        assertThat(authorities(Map.of("roles", List.of("ADMIN"), "scp", "Profile.Read"))).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void invalidClaimTypesAreRejected() {
        for (var claims : List.<Map<String, Object>>of(Map.of("scope", 42), Map.of("cognito:groups", List.of("CUSTOMER", 1)),
                Map.of("cognito:groups", Map.of("g", "ADMIN")))) {
            assertThatThrownBy(() -> authorities(claims)).isInstanceOf(InvalidBearerTokenException.class);
        }
    }
}
