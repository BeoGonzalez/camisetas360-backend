package com.camisetas360.carrito.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Autoridades de un access token de Cognito ya validado (issuer, firma, client_id, token_use y sub UUID).
 * <ul>
 *   <li>{@code cognito:groups} filtrado por allowlist exacta a {@code ROLE_*}; sin grupo permitido se usa
 *       el rol por defecto (CUSTOMER), igual que en el servicio auth.</li>
 *   <li>{@code SCOPE_Checkout.Create} se concede siempre: los tokens del login con usuario y contrasena
 *       (Lambda {@code camisetas360-cognito-login}) no pueden traer scopes personalizados.</li>
 *   <li>{@code roles}, {@code scp} y {@code scope} se ignoran: una Lambda Pre Token Generation podria
 *       inyectarlos.</li>
 * </ul>
 */
public class CognitoAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    static final String GROUPS_CLAIM = "cognito:groups";
    static final String CHECKOUT_SCOPE = "SCOPE_Checkout.Create";

    private final Set<String> allowedGroups;
    private final String defaultRole;

    public CognitoAuthoritiesConverter(CognitoProperties properties) {
        this.allowedGroups = Set.copyOf(properties.allowedGroups());
        this.defaultRole = properties.defaultRole();
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority(CHECKOUT_SCOPE));
        groups(jwt.getClaims().get(GROUPS_CLAIM))
                .filter(allowedGroups::contains)
                .map(group -> new SimpleGrantedAuthority("ROLE_" + group))
                .forEach(authorities::add);
        if (authorities.size() == 1 && defaultRole != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + defaultRole));
        }
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
