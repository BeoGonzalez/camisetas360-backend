package com.camisetas360.auth.controller;

import com.camisetas360.auth.dtos.UserProfileDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    private final AuthController controller = new AuthController();

    // UT-AUTH-001
    @Test
    void getAuthenticatedUserProfile_shouldMapClaims_whenClaimsArePresent() {
        var response = controller.getAuthenticatedUserProfile(jwtWithout(null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(
                new UserProfileDTO("user-123", "tenant-456", "buyer@example.test", "María Pérez"));
    }

    // UT-AUTH-002
    @ParameterizedTest
    @MethodSource("missingClaims")
    void getAuthenticatedUserProfile_shouldPreserveNull_whenClaimIsMissing(
            String missingClaim, UserProfileDTO expected) {
        var response = controller.getAuthenticatedUserProfile(jwtWithout(missingClaim));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    static Stream<Arguments> missingClaims() {
        return Stream.of(
                Arguments.of("oid", new UserProfileDTO(null, "tenant-456", "buyer@example.test", "María Pérez")),
                Arguments.of("tid", new UserProfileDTO("user-123", null, "buyer@example.test", "María Pérez")),
                Arguments.of("preferred_username", new UserProfileDTO("user-123", "tenant-456", null, "María Pérez")),
                Arguments.of("name", new UserProfileDTO("user-123", "tenant-456", "buyer@example.test", null)));
    }

    private static Jwt jwtWithout(String missingClaim) {
        var claims = new HashMap<String, Object>(Map.of(
                "oid", "user-123", "tid", "tenant-456",
                "preferred_username", "buyer@example.test", "name", "María Pérez"));
        claims.remove(missingClaim);
        return Jwt.withTokenValue("unit-test-token").header("alg", "none")
                .claims(values -> values.putAll(claims)).build();
    }
}
