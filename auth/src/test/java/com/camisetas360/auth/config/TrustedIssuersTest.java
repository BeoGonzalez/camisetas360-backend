package com.camisetas360.auth.config;

import com.camisetas360.auth.identity.ClaimsMapper;
import com.camisetas360.auth.identity.EntraClaimsMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TrustedIssuersTest {

    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final ClaimsMapper cognito = mock(ClaimsMapper.class);
    private final ClaimsMapper entra = new EntraClaimsMapper();
    private final TrustedIssuers issuers = new TrustedIssuers(
            Map.of("https://cognito.example.test/pool", new TrustedIssuers.Trust(manager, cognito)), entra);

    @Test
    void exactIssuerSelectsItsManagerAndMapper() {
        assertThat(issuers.authenticationManager("https://cognito.example.test/pool")).isSameAs(manager);
        assertThat(issuers.forIssuer("https://cognito.example.test/pool")).isSameAs(cognito);
    }

    @Test
    void unknownOrMissingIssuerHasNoManagerAndUsesTheHistoricalMapper() {
        for (String issuer : new String[] {null, "https://cognito.example.test/pool/", "https://COGNITO.example.test/pool"}) {
            assertThat(issuers.authenticationManager(issuer)).isNull();
            assertThat(issuers.forIssuer(issuer)).isSameAs(entra);
        }
    }
}
