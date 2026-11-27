package com.camisetas360.pago.webpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.camisetas360.pago.webpay.WebpayPlusPort.WebpayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TransbankWebpayPlusAdapterTest {

    private static final String BASE = "https://webpay3gint.transbank.cl";
    private static final String TX = BASE + TransbankWebpayPlusAdapter.TRANSACCIONES;

    private MockRestServiceServer server;
    private TransbankWebpayPlusAdapter adapter;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE)
                .defaultHeader("Tbk-Api-Key-Id", "597055555532")
                .defaultHeader("Tbk-Api-Key-Secret", "secreto");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new TransbankWebpayPlusAdapter(builder.build(), new ObjectMapper());
    }

    @Test
    void crearEnviaLaTransaccionConLasCredencialesDelComercio() {
        server.expect(requestTo(TX)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Tbk-Api-Key-Id", "597055555532"))
                .andExpect(header("Tbk-Api-Key-Secret", "secreto"))
                .andExpect(content().json("""
                        {"buy_order":"C360ABC","session_id":"sesion","amount":15990,
                         "return_url":"https://front/api/v1/pago/webpay/retorno"}"""))
                .andRespond(withSuccess("""
                        {"token":"01ab","url":"https://webpay3gint.transbank.cl/webpayserver/initTransaction"}""",
                        MediaType.APPLICATION_JSON));

        var tx = adapter.crear("C360ABC", "sesion", 15990, "https://front/api/v1/pago/webpay/retorno");

        assertThat(tx.token()).isEqualTo("01ab");
        assertThat(tx.url()).isEqualTo("https://webpay3gint.transbank.cl/webpayserver/initTransaction");
        server.verify();
    }

    @Test
    void crearSinTokenEsError() {
        server.expect(requestTo(TX)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.crear("C360", "s", 1, "https://r")).isInstanceOf(WebpayException.class);
    }

    @Test
    void confirmarAutorizada() {
        server.expect(requestTo(TX + "/01ab")).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("""
                        {"vci":"TSY","amount":15990,"status":"AUTHORIZED","buy_order":"C360ABC","session_id":"s",
                         "card_detail":{"card_number":"6623"},"accounting_date":"1120",
                         "transaction_date":"2026-11-20T18:00:00.000Z","authorization_code":"1213",
                         "payment_type_code":"VN","response_code":0,"installments_number":0}""",
                        MediaType.APPLICATION_JSON));

        var r = adapter.confirmar("01ab");

        assertThat(r.aprobada()).isTrue();
        assertThat(r).extracting("estado", "codigoRespuesta", "codigoAutorizacion", "ultimos4", "ordenCompra",
                "monto", "tipoPago").containsExactly("AUTHORIZED", 0, "1213", "6623", "C360ABC", 15990L, "VN");
    }

    @Test
    void confirmarRechazada() {
        server.expect(requestTo(TX + "/01ab")).andRespond(withSuccess("""
                {"status":"FAILED","response_code":-1,"card_detail":{"card_number":"0568"},"buy_order":"C360ABC"}""",
                MediaType.APPLICATION_JSON));

        var r = adapter.confirmar("01ab");

        assertThat(r.aprobada()).isFalse();
        assertThat(r.estado()).isEqualTo("FAILED");
        assertThat(r.codigoRespuesta()).isEqualTo(-1);
        assertThat(r.ultimos4()).isEqualTo("0568");
    }

    @Test
    void autorizadaConCodigoDistintoDeCeroNoSeApruebaYSinTarjetaNiMontoQuedanNulos() {
        server.expect(requestTo(TX + "/01ab")).andRespond(withSuccess("""
                {"status":"AUTHORIZED","response_code":-3}""", MediaType.APPLICATION_JSON));

        var r = adapter.confirmar("01ab");

        assertThat(r.aprobada()).isFalse();
        assertThat(r.ultimos4()).isNull();
        assertThat(r.monto()).isNull();
    }

    @Test
    void estadoConsultaConGet() {
        server.expect(requestTo(TX + "/01ab")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":"AUTHORIZED","response_code":0,"authorization_code":"1213"}""",
                        MediaType.APPLICATION_JSON));

        assertThat(adapter.estado("01ab").aprobada()).isTrue();
    }

    @Test
    void errorHttpDeTransbankSeInformaConSuEstado() {
        server.expect(requestTo(TX + "/01ab")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error_message\":\"Invalid status '2' for transaction while authorizing\"}"));

        assertThatThrownBy(() -> adapter.confirmar("01ab"))
                .isInstanceOfSatisfying(WebpayException.class, e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void errorDeRedSeInformaSinEstado() {
        server.expect(requestTo(TX + "/01ab")).andRespond(withException(new IOException("timeout")));

        assertThatThrownBy(() -> adapter.estado("01ab"))
                .isInstanceOfSatisfying(WebpayException.class, e -> assertThat(e.getStatus()).isZero());
    }

    @Test
    void respuestaVaciaEsUnResultadoSinAprobar() {
        server.expect(requestTo(TX + "/01ab")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThat(adapter.estado("01ab").aprobada()).isFalse();
    }
}
