package com.camisetas360.auth.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Autoridades de un access token de Cognito.
 * <ul>
 *   <li>{@code scope}: solo {@code <resource-server-id>/X} se traduce a {@code SCOPE_X}; el resto se ignora.</li>
 *   <li>{@code cognito:groups}: filtrado por allowlist exacta (case-sensitive) a {@code ROLE_*}; sin grupos
 *       validos se asigna {@code ROLE_<default-role>}.</li>
 *   <li>{@code roles} y {@code scp} se ignoran: una Lambda Pre Token Generation podria inyectarlos.</li>
 * </ul>
 */
public class CognitoAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    static final String GROUPS_CLAIM = "cognito:groups";

    private final String scopePrefix;
    private final Set<String> allowedGroups;
    private final String defaultRole;

    public CognitoAuthoritiesConverter(CognitoProperties properties) {
        this.scopePrefix = properties.scopePrefix();
        this.allowedGroups = Set.copyOf(properties.allowedGroups());
        this.defaultRole = properties.defaultRole();
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new LinkedHashSet<>();
        values(jwt.getClaims().get("scope"))
                .flatMap(scope -> Arrays.stream(scope.split("\\s+")))
                .filter(scope -> scope.startsWith(scopePrefix) && scope.length() > scopePrefix.length())
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope.substring(scopePrefix.length())))
                .forEach(authorities::add);
        List<String> groups = values(jwt.getClaims().get(GROUPS_CLAIM)).filter(allowedGroups::contains).toList();
        (groups.isEmpty() ? List.of(defaultRole) : groups).stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .forEach(authorities::add);
        return authorities;
    }

    private static Stream<String> values(Object claim) {
        if (claim == null) return Stream.empty();
        if (claim instanceof String value) return Stream.of(value);
        if (claim instanceof Collection<?> values && values.stream().allMatch(String.class::isInstance)) {
            return values.stream().map(String.class::cast);
        }
        throw new InvalidBearerTokenException("Authorities claims must be strings or collections of strings");
    }
}
