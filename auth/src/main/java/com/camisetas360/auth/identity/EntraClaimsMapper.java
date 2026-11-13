package com.camisetas360.auth.identity;

import com.camisetas360.auth.dtos.UserProfileDTO;
import org.springframework.security.oauth2.jwt.Jwt;

/** Microsoft Entra ID: mapeo historico sin cambios. */
public class EntraClaimsMapper implements ClaimsMapper {
    @Override
    public UserProfileDTO map(Jwt jwt) {
        return new UserProfileDTO(jwt.getClaimAsString("oid"), jwt.getClaimAsString("tid"),
                jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("name"));
    }
}
