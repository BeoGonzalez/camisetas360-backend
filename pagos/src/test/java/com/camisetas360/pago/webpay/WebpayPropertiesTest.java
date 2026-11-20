package com.camisetas360.pago.webpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebpayPropertiesTest {

    @Test
    void normalizaBarrasFinalesYTimeoutPorDefecto() {
        var p = new WebpayProperties(" https://webpay3gint.transbank.cl/ ", "597055555532", "k",
                "https://f/api/v1/pago/webpay/retorno", "https://f/pago/resultado/", null);

        assertThat(p.baseUrl()).isEqualTo("https://webpay3gint.transbank.cl");
        assertThat(p.resultadoUrl()).isEqualTo("https://f/pago/resultado");
        assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void exigeCredencialesYUrlsValidas() {
        assertThatThrownBy(() -> new WebpayProperties("https://w", " ", "k", "https://r", "https://f", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("codigo-comercio");
        assertThatThrownBy(() -> new WebpayProperties("https://w", "c", "k", "sin-esquema", "https://f", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("URL invalida");
    }
}
