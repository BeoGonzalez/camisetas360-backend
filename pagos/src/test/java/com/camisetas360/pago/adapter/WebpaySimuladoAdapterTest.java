package com.camisetas360.pago.adapter;

import com.camisetas360.pago.port.ConfirmarPagoResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class WebpaySimuladoAdapterTest {

    private final WebpaySimuladoAdapter adapter = new WebpaySimuladoAdapter("http://localhost:8087");

    @ParameterizedTest(name = "{0} terminada en {1} -> autorizada={2}")
    @DisplayName("Tarjetas de prueba oficiales de Transbank producen el resultado documentado")
    @CsvSource({
            "VISA,               6623, true",
            "AMEX,               2032, true",
            "MASTERCARD,         0568, false",
            "Redcompra,          7763, true",
            "Redcompra,          7060, true",
            "Redcompra,          3829, false",
            "Prepago VISA,       6590, true",
            "Prepago MASTERCARD, 9480, false"
    })
    void tarjetasDePruebaTransbank(String tipo, String ultimos4, boolean autorizada) {
        ConfirmarPagoResult result = adapter.confirmarTransaccion("abcdef1234567890", ultimos4);

        assertThat(result.autorizada()).as(tipo).isEqualTo(autorizada);
        if (autorizada) {
            assertThat(result.codigoAutorizacion()).isEqualTo("AUTH-ABCDEF12");
            assertThat(result.responseCode()).isEqualTo("0");
        } else {
            assertThat(result.codigoAutorizacion()).isNull();
            assertThat(result.responseCode()).isEqualTo("-1");
        }
    }

    @ParameterizedTest(name = "tarjeta no Transbank terminada en {0} -> autorizada={1}")
    @DisplayName("Tarjetas que no son de prueba Transbank mantienen la regla por ultimo digito")
    @CsvSource({
            "9012, true",
            "1110, false",
            "2225, false"
    })
    void reglaPorUltimoDigitoSeMantiene(String ultimos4, boolean autorizada) {
        assertThat(adapter.confirmarTransaccion("tok123456789", ultimos4).autorizada()).isEqualTo(autorizada);
    }
}
