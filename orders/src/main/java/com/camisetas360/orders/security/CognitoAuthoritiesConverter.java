package com.camisetas360.orders.security;

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
 * Autoridades de un access token de Cognito ya validado (issuer, firma, client_id, token_use y sub).
 * <ul>
 *   <li>{@code cognito:groups} filtrado por allowlist exacta a {@code ROLE_*}; sin grupo permitido se
 *       usa el rol por defecto, si hay uno.</li>
 *   <li>Los {@code scopes} configurados se conceden siempre: los tokens del login con usuario y contrasena
 *       (Lambda {@code camisetas360-cognito-login}) no pueden traer scopes personalizados. La autorizacion
 *       real la da el rol.</li>
 *   <li>{@code roles}, {@code scp} y {@code scope} se ignoran: una Lambda Pre Token Generation podria
 *       inyectarlos.</li>
 * </ul>
 */
public class CognitoAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    static final String GROUPS_CLAIM = "cognito:groups";

    private final Set<String> allowedGroups;
    private final String defaultRole;
    private final List<String> scopes;

    public CognitoAuthoritiesConverter(CognitoProperties properties) {
        this.allowedGroups = Set.copyOf(properties.allowedGroups());
        this.defaultRole = properties.defaultRole();
        this.scopes = properties.scopes();
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> roles = new LinkedHashSet<>();
        groups(jwt.getClaims().get(GROUPS_CLAIM))
                .filter(allowedGroups::contains)
                .map(group -> new SimpleGrantedAuthority("ROLE_" + group))
                .forEach(roles::add);
        if (roles.isEmpty() && defaultRole != null) {
            roles.add(new SimpleGrantedAuthority("ROLE_" + defaultRole));
        }
        Collection<GrantedAuthority> authorities = new LinkedHashSet<>();
        scopes.forEach(scope -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope)));
        authorities.addAll(roles);
        return authorities;
    }

    private static Stream<String> groups(Object claim) {
        if (claim == null) return Stream.empty();
        if (claim instanceof String value) return Stream.of(value);
        if (claim instanceof Collection<?> values && values.stream().allMatch(String.class::isInstance)) {
            return values.stream().map(String.class::cast);
        }
        throw new InvalidBearerTokenException("cognito:groups debe ser un texto o una lista de textos");
    }
}
