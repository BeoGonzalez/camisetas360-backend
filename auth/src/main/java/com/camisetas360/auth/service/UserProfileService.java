package com.camisetas360.auth.service;

import com.camisetas360.auth.dtos.UserProfileDTO;
import com.camisetas360.auth.repository.UserProfileRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
    private final UserProfileRepository profiles;
    public UserProfileService(UserProfileRepository profiles) { this.profiles = profiles; }

    @Transactional
    public UserProfileDTO synchronize(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new InvalidBearerTokenException("El JWT debe identificar issuer y subject para persistir el perfil");
        }
        var profile = new UserProfileDTO(jwt.getClaimAsString("oid"), jwt.getClaimAsString("tid"),
                jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("name"));
        profiles.upsert(issuer, subject, profile.userId(), profile.tenantId(), profile.email(), profile.name());
        return profile;
    }
}
