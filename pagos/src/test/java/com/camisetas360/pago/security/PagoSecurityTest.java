package com.camisetas360.pago.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.camisetas360.pago.controller.PagoController;
import com.camisetas360.pago.dto.PagoResponseDTO;
import com.camisetas360.pago.exception.GlobalExceptionHandler;
import com.camisetas360.pago.model.EstadoPago;
import com.camisetas360.pago.service.PagoService;
import com.camisetas360.pago.webpay.WebpayController;
import com.camisetas360.pago.webpay.WebpayPagoService;
import com.camisetas360.pago.webpay.WebpayProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Reglas de acceso de pagos: que endpoints son publicos y que rol + scope exige cada uno. */
@WebMvcTest(controllers = {PagoController.class, WebpayController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class, PagoSecurityTest.Config.class})
class PagoSecurityTest {

    private static final UUID ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String INICIAR_WEBPAY = "{\"ordenId\":\"" + ID + "\",\"monto\":15000}";

    @TestConfiguration
    static class Config {
        @Bean
        WebpayProperties webpayProperties() {
            return new WebpayProperties("https://webpay3gint.transbank.cl", "597055555532", "k",
                    "https://front.example/api/v1/pago/webpay/retorno", "https://front.example/pago/resultado",
                    Duration.ofSeconds(5));
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean PagoService pagos;
    @MockitoBean WebpayPagoService webpay;

    private static JwtRequestPostProcessor token(String... authorities) {
        return jwt().authorities(java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new));
    }

    private static PagoResponseDTO pago() {
        return new PagoResponseDTO(ID, ID, BigDecimal.TEN, "CLP", EstadoPago.AUTORIZADO, "t", "A1", "1234",
                "VISA", "ok", Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void sinTokenLosEndpointsDeNegocioResponden401() throws Exception {
        mvc.perform(post("/api/v1/pago/webpay/iniciar").contentType(MediaType.APPLICATION_JSON).content(INICIAR_WEBPAY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/pago/iniciar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pago/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pago/orden/" + ID)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pago/alertas")).andExpect(status().isUnauthorized());
    }

    @Test
    void losRetornosDeWebpaySonPublicos() throws Exception {
        when(webpay.procesarRetorno(any(), any(), any())).thenReturn(new WebpayPagoService.Retorno(false, Optional.empty()));
        mvc.perform(get("/api/v1/pago/webpay/retorno").param("token_ws", "t")).andExpect(status().isSeeOther());
        mvc.perform(post("/api/v1/pago/webpay/retorno").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("TBK_TOKEN", "t")).andExpect(status().isSeeOther());
        when(pagos.confirmarPago("t")).thenReturn(pago());
        mvc.perform(get("/api/v1/pago/retorno").param("token_ws", "t")).andExpect(status().isOk());
    }

    @Test
    void clienteConScopeDeCheckoutPuedePagar() throws Exception {
        when(webpay.iniciar(any(), any())).thenReturn(new WebpayPagoService.Inicio(ID, ID, BigDecimal.TEN, "t", "https://w"));
        mvc.perform(post("/api/v1/pago/webpay/iniciar").with(token("ROLE_CUSTOMER", "SCOPE_Checkout.Create"))
                        .contentType(MediaType.APPLICATION_JSON).content(INICIAR_WEBPAY))
                .andExpect(status().isCreated());
    }

    @Test
    void pagarExigeRolYScope() throws Exception {
        mvc.perform(post("/api/v1/pago/webpay/iniciar").with(token("ROLE_CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(INICIAR_WEBPAY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pago/webpay/iniciar").with(token("SCOPE_Checkout.Create"))
                        .contentType(MediaType.APPLICATION_JSON).content(INICIAR_WEBPAY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pago/webpay/iniciar").with(token("ROLE_ADMIN", "SCOPE_Checkout.Create"))
                        .contentType(MediaType.APPLICATION_JSON).content(INICIAR_WEBPAY))
                .andExpect(status().isForbidden());
    }

    @Test
    void consultarPagosExigeOrdersRead() throws Exception {
        when(pagos.obtenerPagoPorId(ID)).thenReturn(pago());
        when(pagos.obtenerPagosPorOrden(ID)).thenReturn(List.of(pago()));
        mvc.perform(get("/api/v1/pago/" + ID).with(token("ROLE_CUSTOMER", "SCOPE_Orders.Read"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/pago/orden/" + ID).with(token("ROLE_ADMIN", "SCOPE_Orders.Read"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/pago/" + ID).with(token("ROLE_CUSTOMER", "SCOPE_Checkout.Create")))
                .andExpect(status().isForbidden());
    }

    @Test
    void alertasDeFraudeSoloParaAdmin() throws Exception {
        when(pagos.obtenerAlertasFraude()).thenReturn(List.of());
        mvc.perform(get("/api/v1/pago/alertas").with(token("ROLE_ADMIN", "SCOPE_Orders.Read"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/pago/alertas").with(token("ROLE_CUSTOMER", "SCOPE_Orders.Read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthEsPublicoYLoDemasSeDeniega() throws Exception {
        mvc.perform(get("/api/v1/pago/desconocido/x").with(token("ROLE_ADMIN", "SCOPE_Orders.Read")))
                .andExpect(status().isForbidden());
    }
}
