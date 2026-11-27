package com.camisetas360.telemetria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.camisetas360.telemetria.auth.ValidadorPorIssuer;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** El Mensajero acepta Entra ID, Cognito o ambos segun las variables de la Lambda. */
class MensajeroValidadorTest {

    @Test
    void cognitoSoloEntraSoloOAmbos() {
        var cognito = Configuracion.desde(Map.of(
                "COGNITO_ISSUER_URI", "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Pool",
                "COGNITO_CLIENT_IDS", " web , otro ,"));
        assertThat(cognito.cognitoConfigurado()).isTrue();
        assertThat(cognito.entraConfigurado()).isFalse();
        assertThat(cognito.cognitoClientIds()).containsExactly("web", "otro");
        assertThat(MensajeroHandler.validador(cognito)).isInstanceOf(ValidadorPorIssuer.class);

        var ambos = Configuracion.desde(Map.of(
                "ENTRA_ISSUER_URI", "https://login.microsoftonline.com/t/v2.0", "ENTRA_AUDIENCE", "api://x",
                "COGNITO_ISSUER_URI", "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_Pool",
                "COGNITO_CLIENT_IDS", "web"));
        assertThat(ambos.entraConfigurado()).isTrue();
        assertThat(MensajeroHandler.validador(ambos)).isNotNull();
    }

    @Test
    void sinNingunIssuerFallaAlArrancar() {
        assertThatThrownBy(() -> MensajeroHandler.validador(Configuracion.desde(Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }
}
