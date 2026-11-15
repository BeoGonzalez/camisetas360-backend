package com.camisetas360.auth.security;

import com.camisetas360.auth.config.SecurityConfig;
import com.camisetas360.auth.config.TrustedIssuers;
import com.camisetas360.auth.controller.AuthController;
import com.camisetas360.auth.repository.UserProfileRepository;
import com.camisetas360.auth.service.UserProfileService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * COGNITO_ENABLED=false (valor por defecto de application.yaml) aunque el resto de la configuracion de
 * Cognito este informada: un token de Cognito valido se rechaza y solo se confia en Entra ID.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, UserProfileService.class})
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CognitoDisabledIT {

    private static final LocalJwtIssuer ENTRA = new LocalJwtIssuer();
    private static final LocalCognitoIssuer COGNITO = new LocalCognitoIssuer();

    @Autowired private MockMvc mvc;
    @Autowired private TrustedIssuers trustedIssuers;
    @MockitoBean private UserProfileRepository profiles;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ENTRA::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ENTRA::jwksUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences[0]", () -> LocalJwtIssuer.AUDIENCE);
        registry.add("app.security.cognito.issuer-uri", COGNITO::issuer);
        registry.add("app.security.cognito.client-ids", () -> LocalCognitoIssuer.CLIENT_ID);
        registry.add("app.security.cognito.resource-server-id", () -> LocalCognitoIssuer.RESOURCE_SERVER);
    }

    @AfterAll
    static void stopIssuers() {
        ENTRA.close();
        COGNITO.close();
    }

    @Test
    void cognitoToken_returns401_whenCognitoIsDisabled() throws Exception {
        mvc.perform(get("/api/v1/auth/profile").header("Authorization", "Bearer " + COGNITO.token(claims -> { })))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
        verifyNoInteractions(profiles);
    }

    @Test
    void entraToken_keepsWorking_whenCognitoIsDisabled() throws Exception {
        mvc.perform(get("/api/v1/auth/profile").header("Authorization", "Bearer " + ENTRA.token("Profile.Read", "valid")))
                .andExpect(status().isOk());
    }

    @Test
    void onlyEntraIsTrusted() {
        assertThat(trustedIssuers.issuers()).containsExactly(ENTRA.issuer());
    }
}
