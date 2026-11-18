package com.camisetas360.catalog.security;

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * La lectura del catálogo es pública. En las lecturas públicas, un token de un issuer de confianza
 * (Entra ID y, si está habilitado, Cognito) se valida completo: así la visita al detalle de un producto
 * se atribuye al usuario y se publica en Kafka (catalogo.producto-visto -> telemetría y recomendaciones).
 * Un token de otro issuer (o que no se puede leer) se ignora y la petición sigue como anónima.
 *
 * El issuer se mira SIN verificar solo para decidir si se ignora el token; ignorarlo nunca da
 * permisos (anónimo solo puede leer). En el resto de rutas (escrituras) cualquier token se valida.
 */
public class PublicCatalogBearerTokenResolver implements BearerTokenResolver {

    static final RequestMatcher PUBLIC_READS =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/catalog/**");

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();
    private final Set<String> trustedIssuers;

    public PublicCatalogBearerTokenResolver(String entraIssuer) {
        this(Set.of(entraIssuer));
    }

    public PublicCatalogBearerTokenResolver(Set<String> trustedIssuers) {
        this.trustedIssuers = Set.copyOf(trustedIssuers);
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String token = delegate.resolve(request);
        if (token == null || !PUBLIC_READS.matches(request)) {
            return token;
        }
        String issuer = unverifiedIssuer(token);
        return issuer != null && trustedIssuers.contains(issuer) ? token : null;
    }

    private static String unverifiedIssuer(String token) {
        try {
            return JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException | RuntimeException e) {
            return null;
        }
    }
}
