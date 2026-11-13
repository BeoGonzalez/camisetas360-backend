package com.camisetas360.telemetria;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguracionTest {

    @Test
    void valoresPorDefecto_autenticacionActivaYTablaDeLaGuia() {
        var config = Configuracion.desde(Map.of());

        assertThat(config.tablaConexiones()).isEqualTo("ConexionesWebSocket");
        assertThat(config.autenticacionHabilitada()).isTrue();
        assertThat(config.ttlConexion()).isEqualTo(Duration.ofHours(2));
        assertThat(config.webSocketApiEndpoint()).isEmpty();
    }

    @Test
    void leeLasVariablesDeEntorno() {
        var config = Configuracion.desde(Map.of(
                "TABLA_CONEXIONES", " Conexiones ",
                "WEBSOCKET_API_ENDPOINT", "https://abc.execute-api.us-east-1.amazonaws.com/prod",
                "ENTRA_ISSUER_URI", "https://issuer",
                "ENTRA_AUDIENCE", "api://aud",
                "WS_AUTH_ENABLED", "FALSE",
                "CONEXION_TTL_HORAS", "1"));

        assertThat(config.tablaConexiones()).isEqualTo("Conexiones");
        assertThat(config.webSocketApiEndpoint()).endsWith("/prod");
        assertThat(config.issuer()).isEqualTo("https://issuer");
        assertThat(config.audience()).isEqualTo("api://aud");
        assertThat(config.autenticacionHabilitada()).isFalse();
        assertThat(config.ttlConexion()).isEqualTo(Duration.ofHours(1));
    }
}
