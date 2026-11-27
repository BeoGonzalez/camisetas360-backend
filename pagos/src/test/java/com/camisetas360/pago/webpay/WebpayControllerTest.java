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

import com.camisetas360.pago.exception.FraudeDetectadoException;
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
import com.camisetas360.pago.security.SecurityConfig;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;

@WebMvcTest(WebpayController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, WebpayControllerTest.Config.class})
// Usuario autenticado con todos los permisos de pagos: las reglas en si se prueban en PagoSecurityTest.
@WithMockUser(authorities = {"ROLE_CUSTOMER", "ROLE_ADMIN", "SCOPE_Checkout.Create", "SCOPE_Orders.Read"})
class WebpayControllerTest {

    private static final String RESULTADO = "https://front.example/pago/resultado";
    private static final String TID = "11111111-1111-1111-1111-111111111111";
    private static final String OID = "22222222-2222-2222-2222-222222222222";

    /** Usuario Entra con (tid, oid): el antifraude de Webpay limita por esta identidad. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor usuario() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.claim("tid", TID).claim("oid", OID))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_Checkout.Create"));
    }

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
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private WebpayPagoService service;

    @Test
    void iniciarDevuelveTokenYUrlDeWebpay() throws Exception {
        UUID ordenId = UUID.randomUUID();
        UUID pagoId = UUID.randomUUID();
        when(service.iniciar(eq(TID + ":" + OID), eq(ordenId), any())).thenReturn(new WebpayPagoService.Inicio(
                pagoId, ordenId, new BigDecimal("15990"), "tok-1", "https://webpay3gint.transbank.cl/webpayserver/initTransaction"));

        mvc.perform(post("/api/v1/pago/webpay/iniciar").with(usuario()).contentType(MediaType.APPLICATION_JSON)
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
        when(service.iniciar(any(), any(), any())).thenThrow(new PagoInvalidoException("ya pagada"));

        mvc.perform(post("/api/v1/pago/webpay/iniciar").with(usuario()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ordenId\":\"" + UUID.randomUUID() + "\",\"monto\":100}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void iniciarBloqueadoPorAntifraudeResponde429ConRetryAfter() throws Exception {
        UUID ordenId = UUID.randomUUID();
        when(service.iniciar(any(), any(), any())).thenThrow(new FraudeDetectadoException(
                "Bloqueado preventivamente por antifraude", ordenId, null, null, null, 2, 10, 1800));

        mvc.perform(post("/api/v1/pago/webpay/iniciar").with(usuario()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ordenId\":\"" + ordenId + "\",\"monto\":15990}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1800"))
                .andExpect(jsonPath("$.codigo").value("BLOQUEADO_FRAUDE"))
                .andExpect(jsonPath("$.segundosParaReintentar").value(1800));
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
