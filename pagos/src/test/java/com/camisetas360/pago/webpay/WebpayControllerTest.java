package com.camisetas360.pago.webpay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.camisetas360.pago.exception.GlobalExceptionHandler;
import com.camisetas360.pago.exception.PagoInvalidoException;
import com.camisetas360.pago.model.Pago;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WebpayController.class)
@Import({GlobalExceptionHandler.class, WebpayControllerTest.Config.class})
class WebpayControllerTest {

    private static final String RESULTADO = "https://front.example/pago/resultado";

    @TestConfiguration
    static class Config {
        @Bean
        WebpayProperties webpayProperties() {
            return new WebpayProperties("https://webpay3gint.transbank.cl", "597055555532", "k",
                    "https://front.example/api/v1/pago/webpay/retorno", RESULTADO + "/", Duration.ofSeconds(5));
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private WebpayPagoService service;

    @Test
    void iniciarDevuelveTokenYUrlDeWebpay() throws Exception {
        UUID ordenId = UUID.randomUUID();
        UUID pagoId = UUID.randomUUID();
        when(service.iniciar(eq(ordenId), any())).thenReturn(new WebpayPagoService.Inicio(
                pagoId, ordenId, new BigDecimal("15990"), "tok-1", "https://webpay3gint.transbank.cl/webpayserver/initTransaction"));

        mvc.perform(post("/api/v1/pago/webpay/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ordenId\":\"" + ordenId + "\",\"monto\":15990}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok-1"))
                .andExpect(jsonPath("$.url").value("https://webpay3gint.transbank.cl/webpayserver/initTransaction"))
                .andExpect(jsonPath("$.pagoId").value(pagoId.toString()));
    }

    @Test
    void iniciarValidaElCuerpo() throws Exception {
        mvc.perform(post("/api/v1/pago/webpay/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monto\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void iniciarOrdenYaPagadaResponde400() throws Exception {
        when(service.iniciar(any(), any())).thenThrow(new PagoInvalidoException("ya pagada"));

        mvc.perform(post("/api/v1/pago/webpay/iniciar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ordenId\":\"" + UUID.randomUUID() + "\",\"monto\":100}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retornoGetAprobadoRedirigeALaPaginaDeResultado() throws Exception {
        Pago pago = pago();
        when(service.procesarRetorno("tok-1", null, null))
                .thenReturn(new WebpayPagoService.Retorno(true, Optional.of(pago)));

        mvc.perform(get("/api/v1/pago/webpay/retorno").param("token_ws", "tok-1"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RESULTADO + "?estado=APROBADO&pagoId=" + pago.getId()
                        + "&ordenId=" + pago.getOrdenId()))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void retornoPostAnuladoRedirigeComoRechazado() throws Exception {
        Pago pago = pago();
        when(service.procesarRetorno(isNull(), eq("tok-1"), eq("C360X")))
                .thenReturn(new WebpayPagoService.Retorno(false, Optional.of(pago)));

        mvc.perform(post("/api/v1/pago/webpay/retorno").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("TBK_TOKEN=tok-1&TBK_ORDEN_COMPRA=C360X&TBK_ID_SESION=s"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RESULTADO + "?estado=RECHAZADO&pagoId=" + pago.getId()
                        + "&ordenId=" + pago.getOrdenId()));
    }

    @Test
    void retornoDesconocidoRedirigeRechazadoSinIds() throws Exception {
        when(service.procesarRetorno(any(), any(), any()))
                .thenReturn(new WebpayPagoService.Retorno(false, Optional.empty()));

        mvc.perform(get("/api/v1/pago/webpay/retorno"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", RESULTADO + "?estado=RECHAZADO"));
    }

    private static Pago pago() {
        Pago pago = new Pago();
        pago.setId(UUID.randomUUID());
        pago.setOrdenId(UUID.randomUUID());
        return pago;
    }
}
