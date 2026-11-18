package com.camisetas360.catalog.security;

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
 * Autoridades de un access token de Cognito ya validado (issuer, firma, client_id y token_use).
 * <ul>
 *   <li>{@code cognito:groups} filtrado por allowlist exacta a {@code ROLE_*}. Sin grupo valido no hay rol:
 *       el token solo sirve para las lecturas, que ya son publicas.</li>
 *   <li>{@code SCOPE_Catalog.Read} se concede siempre: los tokens del login con usuario y contrasena
 *       (Lambda {@code camisetas360-cognito-login}) no pueden traer scopes personalizados, y Cognito es el
 *       login propio de la tienda. La autorizacion real de las escrituras la da el rol.</li>
 *   <li>{@code roles}, {@code scp} y {@code scope} se ignoran: una Lambda Pre Token Generation podria
 *       inyectarlos.</li>
 * </ul>
 */
public class CognitoAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    static final String GROUPS_CLAIM = "cognito:groups";
    static final String CATALOG_SCOPE = "SCOPE_Catalog.Read";

    private final Set<String> allowedGroups;

    public CognitoAuthoritiesConverter(CognitoProperties properties) {
        this.allowedGroups = Set.copyOf(properties.allowedGroups());
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority(CATALOG_SCOPE));
        groups(jwt.getClaims().get(GROUPS_CLAIM))
                .filter(allowedGroups::contains)
                .map(group -> new SimpleGrantedAuthority("ROLE_" + group))
                .forEach(authorities::add);
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
