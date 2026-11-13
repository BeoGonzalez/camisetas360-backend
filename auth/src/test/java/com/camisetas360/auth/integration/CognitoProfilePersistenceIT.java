package com.camisetas360.auth.integration;

import com.camisetas360.auth.model.UserProfile;
import com.camisetas360.auth.repository.UserProfileRepository;
import com.camisetas360.auth.service.UserProfileService;
import com.camisetas360.auth.support.PostgresTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Perfil de Cognito en PostgreSQL real (Flyway V1, sin migraciones nuevas) con userInfo inalcanzable. */
@SpringBootTest(properties = {
        "app.security.cognito.enabled=true",
        "app.security.cognito.issuer-uri=" + CognitoProfilePersistenceIT.ISSUER,
        "app.security.cognito.client-ids=client",
        "app.security.cognito.resource-server-id=camisetas360-api",
        "app.security.cognito.user-info-uri=http://127.0.0.1:1/oauth2/userInfo",
        "app.security.cognito.user-info-timeout=300ms"
})
@AutoConfigureMockMvc
class CognitoProfilePersistenceIT extends PostgresTestSupport {

    static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Persist";
    private static final String POOL = "us-east-1_Persist";

    @Autowired UserProfileService service;
    @Autowired UserProfileRepository profiles;
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void cleanProfiles() { profiles.deleteAll(); }

    @Test void cognitoProfileIsPersistedByIssuerAndSubWithLambdaClaims() {
        var response = service.synchronize(token("sub-1", Map.of("email", "c@example.test", "name", "Cliente")));
        assertThat(response.userId()).isEqualTo("sub-1");
        assertThat(response.tenantId()).isEqualTo(POOL);
        var stored = profiles.findById(new UserProfile.Identity(ISSUER, "sub-1")).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo("sub-1");
        assertThat(stored.getTenantId()).isEqualTo(POOL);
        assertThat(stored.getEmail()).isEqualTo("c@example.test");
        assertThat(stored.getName()).isEqualTo("Cliente");
    }

    @Test void cognitoProfileWithoutContactClaimsIsPersistedWithNulls() {
        var response = service.synchronize(token("sub-2", Map.of()));
        assertThat(response.email()).isNull();
        assertThat(response.name()).isNull();
        var stored = profiles.findById(new UserProfile.Identity(ISSUER, "sub-2")).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo("sub-2");
        assertThat(stored.getEmail()).isNull();
        assertThat(stored.getName()).isNull();
    }

    @Test void missingContactDoesNotEraseStoredValuesButNewValuesUpdateThem() {
        service.synchronize(token("sub-3", Map.of("email", "old@example.test", "name", "Old")));
        var kept = service.synchronize(token("sub-3", Map.of()));
        assertThat(kept.email()).isEqualTo("old@example.test");
        assertThat(kept.name()).isEqualTo("Old");
        service.synchronize(token("sub-3", Map.of("email", "new@example.test")));
        var stored = profiles.findById(new UserProfile.Identity(ISSUER, "sub-3")).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo("new@example.test");
        assertThat(stored.getName()).isEqualTo("Old");
        assertThat(profiles.count()).isEqualTo(1);
    }

    @Test void entraAndCognitoSubjectsNeverCollide() {
        service.synchronize(token("same", Map.of("email", "cognito@example.test")));
        service.synchronize(Jwt.withTokenValue("t").header("alg", "none").issuer("https://login.example.test/v2.0")
                .subject("same").claim("oid", "o").claim("tid", "t").claim("preferred_username", "entra@example.test").build());
        assertThat(profiles.count()).isEqualTo(2);
        assertThat(profiles.findById(new UserProfile.Identity(ISSUER, "same")).orElseThrow().getEmail())
                .isEqualTo("cognito@example.test");
    }

    @Test void profileEndpointReturns200WhenUserInfoIsUnavailable() throws Exception {
        mvc.perform(get("/api/v1/auth/profile").with(jwt()
                        .jwt(token -> token.issuer(ISSUER).subject("sub-4").claims(c -> {
                            c.remove("scope");
                            c.put("token_use", "access");
                        }))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"), new SimpleGrantedAuthority("SCOPE_Profile.Read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("sub-4"))
                .andExpect(jsonPath("$.tenantId").value(POOL))
                .andExpect(jsonPath("$.email").doesNotExist());
        assertThat(profiles.findById(new UserProfile.Identity(ISSUER, "sub-4"))).isPresent();
    }

    private static Jwt token(String subject, Map<String, Object> claims) {
        return Jwt.withTokenValue("access-token").header("alg", "none").issuer(ISSUER).subject(subject)
                .claim("token_use", "access").claims(c -> c.putAll(claims)).build();
    }
}
