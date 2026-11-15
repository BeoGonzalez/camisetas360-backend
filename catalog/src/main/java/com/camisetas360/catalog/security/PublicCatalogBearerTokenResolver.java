package com.camisetas360.catalog.security;

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.http.HttpServletRequest;
import java.text.ParseException;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * La lectura del catálogo es pública. Un visitante logueado con otro proveedor (Cognito) igual
 * envía su Bearer, y el decoder de Entra lo rechazaría con 401: en las lecturas públicas, un token
 * que no es de Entra (o no se puede leer) se ignora y la petición sigue como anónima.
 *
 * El issuer se mira SIN verificar solo para decidir si se ignora el token; ignorarlo nunca da
 * permisos (anónimo solo puede leer). Los tokens de Entra se validan completos, y en el resto de
 * rutas (escrituras) cualquier token se valida como siempre.
 */
public class PublicCatalogBearerTokenResolver implements BearerTokenResolver {

    static final RequestMatcher PUBLIC_READS =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/catalog/**");

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();
    private final String entraIssuer;

    public PublicCatalogBearerTokenResolver(String entraIssuer) {
        this.entraIssuer = entraIssuer;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String token = delegate.resolve(request);
        if (token == null || !PUBLIC_READS.matches(request)) {
            return token;
        }
        return entraIssuer.equals(unverifiedIssuer(token)) ? token : null;
    }

    private static String unverifiedIssuer(String token) {
        try {
            return JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException | RuntimeException e) {
            return null;
        }
    }
}
