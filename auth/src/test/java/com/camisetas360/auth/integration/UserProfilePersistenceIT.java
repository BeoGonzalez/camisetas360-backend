package com.camisetas360.auth.integration;

import com.camisetas360.auth.model.UserProfile;
import com.camisetas360.auth.repository.UserProfileRepository;
import com.camisetas360.auth.service.UserProfileService;
import com.camisetas360.auth.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class UserProfilePersistenceIT extends PostgresTestSupport {
    @Autowired UserProfileService service;
    @Autowired UserProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void cleanProfiles() { profiles.deleteAll(); }

    @Test void profileIsPersistedAndUpdatedWithoutDuplicatingItsIdentity() {
        service.synchronize(token("https://issuer.example.test", "person-1", "old@example.test", "Old"));
        var response = service.synchronize(token("https://issuer.example.test", "person-1", "new@example.test", "Nuevo 東京"));
        assertThat(response.email()).isEqualTo("new@example.test");
        assertThat(profiles.count()).isEqualTo(1);
        var stored = profiles.findById(new UserProfile.Identity("https://issuer.example.test", "person-1")).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo("new@example.test");
        assertThat(stored.getName()).isEqualTo("Nuevo 東京");
        assertThat(stored.getUserId()).isEqualTo("object-1");
        assertThat(stored.getTenantId()).isEqualTo("tenant-1");
        assertThat(jdbc.queryForObject("SELECT email FROM user_profiles", String.class)).isEqualTo("new@example.test");
    }

    @Test void identicalSubjectsFromDifferentIssuersHaveSeparateProfiles() {
        service.synchronize(token("https://one.example.test", "same", "one@example.test", "One"));
        service.synchronize(token("https://two.example.test", "same", "two@example.test", "Two"));
        assertThat(profiles.count()).isEqualTo(2);
        assertThat(profiles.findById(new UserProfile.Identity("https://one.example.test", "same"))
                .orElseThrow().getEmail()).isEqualTo("one@example.test");
        assertThat(profiles.findById(new UserProfile.Identity("https://two.example.test", "same"))
                .orElseThrow().getEmail()).isEqualTo("two@example.test");
    }

    @Test void optionalClaimsRemainNullInTheDatabaseAndResponse() {
        var jwt = Jwt.withTokenValue("test").header("alg", "none")
                .issuer("https://issuer.example.test").subject("person-1").build();
        var response = service.synchronize(jwt);
        assertThat(response.email()).isNull();
        assertThat(response.name()).isNull();
        assertThat(response.userId()).isNull();
        assertThat(response.tenantId()).isNull();
        var row = jdbc.queryForMap("SELECT user_id, tenant_id, email, name FROM user_profiles");
        assertThat(row.values()).containsOnlyNulls();
    }

    @Test void missingStableJwtIdentityDoesNotCreateAProfile() {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").claim("name", "Buyer").build();
        assertThatThrownBy(() -> service.synchronize(jwt))
                .isInstanceOf(org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class);
        assertThat(profiles.count()).isZero();
    }

    @Test void flywayAndTheCompositePrimaryKeyAreRealPostgreSqlConstraints() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success", String.class)).isEqualTo("1");
        service.synchronize(token("https://issuer.example.test", "person-1", "buyer@example.test", "Buyer"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_profiles (issuer, subject) VALUES (?, ?)",
                "https://issuer.example.test", "person-1"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_profiles (issuer, subject) VALUES (NULL, 'invalid')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(profiles.count()).isEqualTo(1);
    }

    private static Jwt token(String issuer, String subject, String email, String name) {
        return Jwt.withTokenValue("test").header("alg", "none").issuer(issuer).subject(subject)
                .claim("oid", "object-1").claim("tid", "tenant-1")
                .claim("preferred_username", email).claim("name", name).build();
    }
}
