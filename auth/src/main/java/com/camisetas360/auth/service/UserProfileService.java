package com.camisetas360.auth.service;

import com.camisetas360.auth.dtos.UserProfileDTO;
import com.camisetas360.auth.identity.ClaimsMapperResolver;
import com.camisetas360.auth.model.UserProfile;
import com.camisetas360.auth.repository.UserProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;

/**
 * Upsert del perfil por (issuer, subject), agnostico al proveedor. Sin {@code @Transactional} a nivel de
 * servicio: el mapeo puede llamar a userInfo de Cognito y no debe retener una conexion de BD durante HTTP;
 * cada operacion del repositorio es transaccional por si misma.
 */
@Service
public class UserProfileService {
    private final UserProfileRepository profiles;
    private final ClaimsMapperResolver mappers;

    @Autowired
    public UserProfileService(UserProfileRepository profiles, ClaimsMapperResolver mappers) {
        this.profiles = profiles;
        this.mappers = mappers;
    }

    /** Solo Entra ID (comportamiento historico). */
    public UserProfileService(UserProfileRepository profiles) {
        this(profiles, ClaimsMapperResolver.entraOnly());
    }

    public UserProfileDTO synchronize(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new InvalidBearerTokenException("El JWT debe identificar issuer y subject para persistir el perfil");
        }
        var mapper = mappers.forIssuer(issuer);
        var profile = mapper.map(jwt);
        if (!mapper.keepsStoredContactWhenMissing()) {
            profiles.upsert(issuer, subject, profile.userId(), profile.tenantId(), profile.email(), profile.name());
            return profile;
        }
        profiles.upsertKeepingContact(issuer, subject, profile.userId(), profile.tenantId(), profile.email(), profile.name());
        return profiles.findById(new UserProfile.Identity(issuer, subject))
                .map(stored -> new UserProfileDTO(stored.getUserId(), stored.getTenantId(), stored.getEmail(), stored.getName()))
                .orElse(profile);
    }
}
