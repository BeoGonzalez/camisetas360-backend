package com.camisetas360.testing.security;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import static org.assertj.core.api.Assertions.*;

public interface AuthoritiesContract {
    Converter<Jwt, Collection<GrantedAuthority>> converter();

    default Jwt token(Map<String, Object> claims) {
        return Jwt.withTokenValue("test-only").header("alg", "none")
                .subject("subject").claims(c -> c.putAll(claims)).build();
    }
    default List<String> authorities(Map<String, Object> claims) {
        return converter().convert(token(claims)).stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test default void scopeString() {
        assertThat(authorities(Map.of("scope", "Catalog.Read Orders.Read")))
                .containsExactly("SCOPE_Catalog.Read", "SCOPE_Orders.Read");
    }
    @Test default void scpString() {
        assertThat(authorities(Map.of("scp", "Catalog.Read Orders.Read")))
                .containsExactly("SCOPE_Catalog.Read", "SCOPE_Orders.Read");
    }
    @Test default void scopesAsList() {
        assertThat(authorities(Map.of("scp", List.of("Catalog.Read", "Orders.Read"))))
                .containsExactly("SCOPE_Catalog.Read", "SCOPE_Orders.Read");
    }
    @Test default void rolesAsList() {
        assertThat(authorities(Map.of("roles", List.of("CUSTOMER", "ADMIN", "CATALOG_MANAGER"))))
                .containsExactly("ROLE_CUSTOMER", "ROLE_ADMIN", "ROLE_CATALOG_MANAGER");
    }
    @ParameterizedTest @ValueSource(strings={"CUSTOMER","ADMIN","CATALOG_MANAGER","Customer","Admin"})
    default void roleValuesRemainCaseSensitive(String role) {
        assertThat(authorities(Map.of("roles", List.of(role)))).containsExactly("ROLE_" + role);
    }
    @Test default void roleStringIsOneRole() {
        assertThat(authorities(Map.of("roles", "ADMIN"))).containsExactly("ROLE_ADMIN");
    }
    @Test default void missingClaims() {
        assertThat(authorities(Map.of())).isEmpty();
    }
    @Test default void scopePrecedesScpAsInSpring() {
        assertThat(authorities(Map.of("scope","Catalog.Read","scp","Orders.Read")))
                .containsExactly("SCOPE_Catalog.Read");
    }
    @Test default void emptyScopeDoesNotFallBackToScp() {
        assertThat(authorities(Map.of("scope","","scp","Orders.Read"))).isEmpty();
    }
    @Test default void duplicateAuthoritiesAreRemoved() {
        assertThat(authorities(Map.of("scp","Catalog.Read Catalog.Read","roles",List.of("ADMIN","ADMIN"))))
                .containsExactly("SCOPE_Catalog.Read","ROLE_ADMIN");
    }
    @Test default void blankClaimsDoNotGrantEmptyAuthorities() {
        assertThat(authorities(Map.of("scp"," ","roles",List.of("", " ")))).isEmpty();
    }
    @Test default void invalidScopeTypesAreRejected() {
        for (Object invalid : List.of(42, List.of("Catalog.Read", 42), Map.of("value","Catalog.Read"))) {
            assertThatThrownBy(() -> authorities(Map.of("scp",invalid)))
                    .isInstanceOf(InvalidBearerTokenException.class);
        }
    }
    @Test default void invalidRoleTypesAreRejected() {
        for (Object invalid : List.of(42, List.of("ADMIN",42), Map.of("value","ADMIN"))) {
            assertThatThrownBy(() -> authorities(Map.of("roles",invalid)))
                    .isInstanceOf(InvalidBearerTokenException.class);
        }
    }
}
