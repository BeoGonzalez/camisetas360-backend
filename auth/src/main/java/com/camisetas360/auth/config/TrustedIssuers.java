package com.camisetas360.auth.config;

import com.camisetas360.auth.identity.ClaimsMapper;
import com.camisetas360.auth.identity.ClaimsMapperResolver;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.AuthenticationManager;

/**
 * Registro cerrado de issuers de confianza. Cada issuer (comparado con {@code String.equals}) tiene su
 * propio {@link AuthenticationManager} (decoder + JWKS + validadores + converter de autoridades) y su
 * {@link ClaimsMapper}; un issuer desconocido no obtiene manager y el token se rechaza con 401.
 */
public final class TrustedIssuers implements ClaimsMapperResolver {

    record Trust(AuthenticationManager authenticationManager, ClaimsMapper claimsMapper) { }

    private final Map<String, Trust> trusts;
    private final ClaimsMapper defaultClaimsMapper;

    TrustedIssuers(Map<String, Trust> trusts, ClaimsMapper defaultClaimsMapper) {
        this.trusts = Collections.unmodifiableMap(new LinkedHashMap<>(trusts));
        this.defaultClaimsMapper = defaultClaimsMapper;
    }

    /** Usado por {@code JwtIssuerAuthenticationManagerResolver}; {@code null} = issuer no confiable. */
    public AuthenticationManager authenticationManager(String issuer) {
        var trust = issuer == null ? null : trusts.get(issuer);
        return trust == null ? null : trust.authenticationManager();
    }

    /**
     * Solo se invoca con tokens ya autenticados. Un issuer no registrado (p. ej. tokens sinteticos en tests)
     * conserva el mapeo historico de Entra.
     */
    @Override
    public ClaimsMapper forIssuer(String issuer) {
        var trust = issuer == null ? null : trusts.get(issuer);
        return trust == null ? defaultClaimsMapper : trust.claimsMapper();
    }

    public Set<String> issuers() {
        return trusts.keySet();
    }
}
