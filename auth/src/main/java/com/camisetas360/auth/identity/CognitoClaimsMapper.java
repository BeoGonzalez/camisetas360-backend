package com.camisetas360.auth.identity;

import com.camisetas360.auth.dtos.UserProfileDTO;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Amazon Cognito: {@code userId = sub} (inmutable; {@code username} puede ser un alias),
 * {@code tenantId = userPoolId} (equivalente a {@code tid}: identifica el directorio emisor).
 * email/name vienen de la Lambda Pre Token Generation V2_0 o, si faltan, del endpoint userInfo.
 */
public class CognitoClaimsMapper implements ClaimsMapper {
    private final String userPoolId;
    private final CognitoUserInfoClient userInfo;

    /** @param userInfo cliente del fallback, o {@code null} si no hay {@code user-info-uri}. */
    public CognitoClaimsMapper(String userPoolId, CognitoUserInfoClient userInfo) {
        this.userPoolId = userPoolId;
        this.userInfo = userInfo;
    }

    @Override
    public UserProfileDTO map(Jwt jwt) {
        String email = text(jwt.getClaims().get("email"));
        String name = text(jwt.getClaims().get("name"));
        if ((email == null || name == null) && userInfo != null) {
            var fallback = userInfo.fetch(jwt.getTokenValue());
            email = email != null ? email : fallback.email();
            name = name != null ? name : fallback.name();
        }
        return new UserProfileDTO(jwt.getSubject(), userPoolId, email, name);
    }

    @Override
    public boolean keepsStoredContactWhenMissing() {
        return true;
    }

    static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
