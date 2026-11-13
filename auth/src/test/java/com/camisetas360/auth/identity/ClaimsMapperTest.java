package com.camisetas360.auth.identity;

import com.camisetas360.auth.dtos.UserProfileDTO;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClaimsMapperTest {

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("access-token").header("alg", "none").subject("sub-1")
                .issuer("https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Pool").claims(c -> c.putAll(claims)).build();
    }

    @Test
    void entraMapperKeepsHistoricalClaims() {
        var mapper = ClaimsMapperResolver.entraOnly().forIssuer("any");
        assertThat(mapper.map(jwt(Map.of("oid", "o", "tid", "t", "preferred_username", "u@example.test", "name", "N"))))
                .isEqualTo(new UserProfileDTO("o", "t", "u@example.test", "N"));
        assertThat(mapper.keepsStoredContactWhenMissing()).isFalse();
    }

    @Test
    void cognitoUsesSubAndUserPoolAndTokenContactWithoutCallingUserInfo() {
        var userInfo = mock(CognitoUserInfoClient.class);
        var mapper = new CognitoClaimsMapper("us-east-1_Pool", userInfo);
        assertThat(mapper.map(jwt(Map.of("email", "c@example.test", "name", "C", "oid", "ignored"))))
                .isEqualTo(new UserProfileDTO("sub-1", "us-east-1_Pool", "c@example.test", "C"));
        assertThat(mapper.keepsStoredContactWhenMissing()).isTrue();
        verifyNoInteractions(userInfo);
    }

    @Test
    void cognitoFillsOnlyMissingContactFromUserInfo() {
        var userInfo = mock(CognitoUserInfoClient.class);
        when(userInfo.fetch("access-token")).thenReturn(new CognitoUserInfoClient.UserInfo("ui@example.test", "UI"));
        var mapper = new CognitoClaimsMapper("us-east-1_Pool", userInfo);
        assertThat(mapper.map(jwt(Map.of("email", "token@example.test", "name", " "))))
                .isEqualTo(new UserProfileDTO("sub-1", "us-east-1_Pool", "token@example.test", "UI"));
        assertThat(mapper.map(jwt(Map.of("name", "Token", "email", 42))))
                .isEqualTo(new UserProfileDTO("sub-1", "us-east-1_Pool", "ui@example.test", "Token"));
    }

    @Test
    void cognitoWithoutUserInfoLeavesContactNull() {
        assertThat(new CognitoClaimsMapper("us-east-1_Pool", null).map(jwt(Map.of())))
                .isEqualTo(new UserProfileDTO("sub-1", "us-east-1_Pool", null, null));
    }
}
