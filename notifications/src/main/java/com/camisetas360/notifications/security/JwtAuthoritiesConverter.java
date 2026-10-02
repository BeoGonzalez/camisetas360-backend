package com.camisetas360.notifications.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

public class JwtAuthoritiesConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {

        Collection<GrantedAuthority> authorities = new ArrayList<>();

        Collection<GrantedAuthority> scopeAuthorities = scopeConverter.convert(jwt);

        if (scopeAuthorities != null) {
            authorities.addAll(scopeAuthorities);
        }

        List<String> roles = jwt.getClaimAsStringList("roles");

        if (roles != null) {
            roles.forEach(role -> authorities.add(
                    new SimpleGrantedAuthority(
                            "ROLE_" + role)));
        }

        return authorities;
    }
}