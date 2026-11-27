package com.camisetas360.telemetria.cognito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpResponse;

class CognitoLoginTest {

    final CognitoIdentityProviderClient cognito = mock(CognitoIdentityProviderClient.class);
    final CognitoLogin login = new CognitoLogin(cognito, "us-east-1_pool", "client", "CUSTOMER", true);

    static InitiateAuthResponse tokens(String refresh) {
        return InitiateAuthResponse.builder().authenticationResult(AuthenticationResultType.builder()
                .accessToken("at").idToken("it").refreshToken(refresh).expiresIn(3600).tokenType("Bearer").build()).build();
    }

    static CognitoIdentityProviderException error(String codigo) {
        return (CognitoIdentityProviderException) CognitoIdentityProviderException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(codigo).build()).build();
    }

    @Test
    void loginDevuelveLosTokensYUsaElEmailEnMinusculas() {
        when(cognito.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(tokens("rt"));

        var r = login.ejecutar("login", Map.of("email", " Ana@Example.com ", "password", "Secreta1"));

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.cuerpo()).containsEntry("accessToken", "at").containsEntry("refreshToken", "rt")
                .containsEntry("expiresIn", 3600).containsEntry("tokenType", "Bearer");
        var req = ArgumentCaptor.forClass(InitiateAuthRequest.class);
        verify(cognito).initiateAuth(req.capture());
        assertThat(req.getValue().authFlow()).isEqualTo(AuthFlowType.USER_PASSWORD_AUTH);
        assertThat(req.getValue().authParameters()).containsEntry("USERNAME", "ana@example.com");
    }

    @Test
    void loginSinDatosResponde400SinLlamarACognito() {
        assertThat(login.ejecutar("login", Map.of("email", "a@b.cl")).status()).isEqualTo(400);
        assertThat(login.ejecutar("login", Map.of("password", 5)).status()).isEqualTo(400);
        verifyNoInteractions(cognito);
    }

    @Test
    void credencialesInvalidasYErroresDesconocidos() {
        when(cognito.initiateAuth(any(InitiateAuthRequest.class)))
                .thenThrow(error("NotAuthorizedException"))
                .thenThrow(error("UserNotConfirmedException"))
                .thenThrow(error("ServicioRaro"));
        var datos = Map.<String, Object>of("email", "a@b.cl", "password", "x");
        assertThat(login.ejecutar("login", datos).status()).isEqualTo(401);
        assertThat(login.ejecutar("login", datos).status()).isEqualTo(403);
        assertThat(login.ejecutar("login", datos).status()).isEqualTo(500);
    }

    @Test
    void desafioNoSoportadoResponde403() {
        when(cognito.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(InitiateAuthResponse.builder()
                .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED).build());
        var r = login.ejecutar("login", Map.of("email", "a@b.cl", "password", "x"));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.cuerpo().get("error").toString()).contains("NEW_PASSWORD_REQUIRED");
    }

    @Test
    void refreshConservaElRefreshTokenAnterior() {
        when(cognito.initiateAuth(any(InitiateAuthRequest.class))).thenReturn(tokens(null));
        var r = login.ejecutar("refresh", Map.of("refreshToken", "rt-viejo"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.cuerpo()).containsEntry("refreshToken", "rt-viejo");
        assertThat(login.ejecutar("refresh", Map.of()).status()).isEqualTo(400);
    }

    @Test
    void registroConfirmaYAgregaAlGrupoCustomer() {
        when(cognito.signUp(any(SignUpRequest.class)))
                .thenReturn(SignUpResponse.builder().userSub("sub-1").userConfirmed(false).build());

        var r = login.ejecutar("register", Map.of("email", "Nuevo@Example.com", "password", "Secreta1"));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.cuerpo()).containsEntry("userSub", "sub-1").containsEntry("email", "nuevo@example.com")
                .containsEntry("confirmed", true);
        var alta = ArgumentCaptor.forClass(SignUpRequest.class);
        verify(cognito).signUp(alta.capture());
        assertThat(alta.getValue().userAttributes()).anyMatch(a -> a.name().equals("name") && a.value().equals("nuevo"));
        verify(cognito).adminConfirmSignUp(any(AdminConfirmSignUpRequest.class));
        var grupo = ArgumentCaptor.forClass(AdminAddUserToGroupRequest.class);
        verify(cognito).adminAddUserToGroup(grupo.capture());
        assertThat(grupo.getValue().groupName()).isEqualTo("CUSTOMER");
    }

    @Test
    void registroSinAutoConfirmarNiGrupoPrivilegiado() {
        var manual = new CognitoLogin(cognito, "pool", "client", "ADMIN", false);
        when(cognito.signUp(any(SignUpRequest.class)))
                .thenReturn(SignUpResponse.builder().userSub("s").userConfirmed(false).build());

        var r = manual.ejecutar("register", Map.of("email", "a@b.cl", "password", "Secreta1", "name", "Ana"));

        assertThat(r.cuerpo()).containsEntry("confirmed", false);
        verify(cognito, never()).adminConfirmSignUp(any(AdminConfirmSignUpRequest.class));
        verify(cognito, never()).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
    }

    @Test
    void registroValidaDatosYTraduceDuplicados() {
        assertThat(login.ejecutar("register", Map.of("email", "no-es-email", "password", "x")).status()).isEqualTo(400);
        assertThat(login.ejecutar("register", Map.of("email", "a@b.cl")).status()).isEqualTo(400);
        when(cognito.signUp(any(SignUpRequest.class))).thenThrow(error("UsernameExistsException"));
        assertThat(login.ejecutar("register", Map.of("email", "a@b.cl", "password", "Secreta1")).status()).isEqualTo(409);
    }

    @Test
    void confirmarYAccionDesconocida() {
        var r = login.ejecutar("confirm", Map.of("email", "A@b.cl", "code", "123456"));
        assertThat(r.status()).isEqualTo(200);
        verify(cognito).confirmSignUp(any(ConfirmSignUpRequest.class));
        assertThat(login.ejecutar("confirm", Map.of("email", "a@b.cl")).status()).isEqualTo(400);
        assertThat(login.ejecutar("borrar", Map.of()).status()).isEqualTo(404);
    }

    @Test
    void faltaConfiguracion() {
        assertThatThrownBy(() -> new CognitoLogin(cognito, " ", "c", "CUSTOMER", true))
                .hasMessageContaining("USER_POOL_ID");
        assertThatThrownBy(() -> new CognitoLogin(cognito, "p", null, "CUSTOMER", true))
                .hasMessageContaining("CLIENT_ID");
    }
}
