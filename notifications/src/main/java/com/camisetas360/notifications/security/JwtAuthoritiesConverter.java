package com.camisetas360.notifications.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

public class JwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        validate(jwt.getClaim("scope"));
        validate(jwt.getClaim("scp"));
        validate(jwt.getClaim("roles"));
        Collection<GrantedAuthority> authorities = new LinkedHashSet<>();
        scopeConverter.convert(jwt).stream()
                .filter(authority -> !authority.getAuthority().equals("SCOPE_"))
                .forEach(authorities::add);
        var roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            roles.stream().filter(role -> !role.isBlank())
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .forEach(authorities::add);
        }
        return authorities;
    }

    private void validate(Object claim) {
        if (claim == null || claim instanceof String) return;
        if (claim instanceof Collection<?> values && values.stream().allMatch(String.class::isInstance)) return;
        throw new InvalidBearerTokenException("Authorities claims must be strings or collections of strings");
    }
}
