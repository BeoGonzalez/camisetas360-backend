package com.camisetas360.orders.security;
import com.camisetas360.testing.security.AuthoritiesContract;
import java.util.Collection;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
class JwtAuthoritiesConverterTest implements AuthoritiesContract {
    public Converter<Jwt, Collection<GrantedAuthority>> converter() { return new JwtAuthoritiesConverter(); }
}
