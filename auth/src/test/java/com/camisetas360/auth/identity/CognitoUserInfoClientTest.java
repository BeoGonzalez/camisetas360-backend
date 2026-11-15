package com.camisetas360.auth.identity;

import com.camisetas360.auth.security.LocalCognitoIssuer;
import com.camisetas360.auth.security.LocalCognitoIssuer.UserInfoMode;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class CognitoUserInfoClientTest {

    private static final LocalCognitoIssuer COGNITO = new LocalCognitoIssuer();
    private final CognitoUserInfoClient client = new CognitoUserInfoClient(COGNITO.userInfoUri(), Duration.ofMillis(500));

    @AfterAll
    static void stop() {
        COGNITO.close();
    }

    @Test
    void returnsEmailAndNameAndForwardsTheBearer() {
        COGNITO.userInfoMode(UserInfoMode.OK);
        assertThat(client.fetch("access-token"))
                .isEqualTo(new CognitoUserInfoClient.UserInfo("fallback@example.test", "Fallback"));
        assertThat(COGNITO.lastUserInfoAuthorization()).isEqualTo("Bearer access-token");
    }

    @ParameterizedTest
    @EnumSource(value = UserInfoMode.class, names = {"ERROR", "SLOW", "REDIRECT", "NOT_JSON", "NON_STRING"})
    void failuresNeverPropagate(UserInfoMode mode) {
        COGNITO.userInfoMode(mode);
        long start = System.nanoTime();
        assertThat(client.fetch("access-token")).isEqualTo(new CognitoUserInfoClient.UserInfo(null, null));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void unreachableEndpointReturnsEmpty() {
        var offline = new CognitoUserInfoClient("http://127.0.0.1:1/oauth2/userInfo", Duration.ofMillis(300));
        assertThat(offline.fetch("access-token")).isEqualTo(new CognitoUserInfoClient.UserInfo(null, null));
    }
}
