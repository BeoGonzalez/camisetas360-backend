package com.camisetas360.recommendation.controller;

import com.camisetas360.recommendation.security.SecurityConfig;
import com.camisetas360.recommendation.service.RecomendacionesConsulta;
import com.camisetas360.recommendation.service.RecomendacionesConsulta.Fuente;
import com.camisetas360.recommendation.service.RecomendacionesConsulta.Item;
import com.camisetas360.recommendation.service.RecomendacionesConsulta.Resultado;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RecomendacionController.class)
@Import(SecurityConfig.class)
class RecomendacionControllerWebMvcTest {

    private static final String USUARIO = "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean RecomendacionesConsulta recomendaciones;

    @Test
    void devuelveElTop5DelUsuarioDelToken() throws Exception {
        when(recomendaciones.consultar(USUARIO)).thenReturn(new Resultado(Fuente.PESOS, null, List.of(
                new Item(1, "CHI-LOC", 6, null, "Camiseta Chile Local 2026", "Selecciones", 29990.0),
                new Item(2, "ARG-LOC", 1, null, null, null, null))));

        mvc.perform(get("/api/v1/recomendaciones").with(token("ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fuente").value("PESOS"))
                .andExpect(jsonPath("$.items[0].posicion").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("CHI-LOC"))
                .andExpect(jsonPath("$.items[0].puntaje").value(6))
                .andExpect(jsonPath("$.items[0].nombre").value("Camiseta Chile Local 2026"))
                .andExpect(jsonPath("$.items[1].sku").value("ARG-LOC"));
    }

    @Test
    void devuelveLasRecomendacionesDelModeloConSuScore() throws Exception {
        when(recomendaciones.consultar(USUARIO)).thenReturn(new Resultado(Fuente.MODELO, "20261112T190000Z", List.of(
                new Item(1, "ARG-VIS", null, 0.9731, "Camiseta Argentina Visita", "Selecciones", 34990.0))));

        mvc.perform(get("/api/v1/recomendaciones").with(token("ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fuente").value("MODELO"))
                .andExpect(jsonPath("$.versionModelo").value("20261112T190000Z"))
                .andExpect(jsonPath("$.items[0].sku").value("ARG-VIS"))
                .andExpect(jsonPath("$.items[0].score").value(0.9731));
    }

    @Test
    void sinHistorialDevuelveListaVacia() throws Exception {
        when(recomendaciones.consultar(USUARIO)).thenReturn(new Resultado(Fuente.PESOS, null, List.of()));

        mvc.perform(get("/api/v1/recomendaciones").with(token("ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void parametroUserIdNoPermiteLeerOtroUsuario() throws Exception {
        when(recomendaciones.consultar(USUARIO)).thenReturn(new Resultado(Fuente.PESOS, null, List.of()));

        mvc.perform(get("/api/v1/recomendaciones").param("userId", "otro:usuario").with(token("ROLE_CUSTOMER")))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(recomendaciones).consultar(USUARIO);
    }

    @Test
    void requiereToken() throws Exception {
        mvc.perform(get("/api/v1/recomendaciones")).andExpect(status().isUnauthorized());
        verifyNoInteractions(recomendaciones);
    }

    @Test
    void requiereRolCustomerYScope() throws Exception {
        mvc.perform(get("/api/v1/recomendaciones").with(token("ROLE_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/recomendaciones").with(jwt().jwt(j -> j.claim("tid", "11111111-1111-1111-1111-111111111111")
                        .claim("oid", "22222222-2222-2222-2222-222222222222"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(recomendaciones);
    }

    @Test
    void otrasRutasYMetodosSeDeniegan() throws Exception {
        mvc.perform(post("/api/v1/recomendaciones").with(token("ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/interacciones").with(token("ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/recommendations").with(token("ROLE_CUSTOMER"))).andExpect(status().isForbidden());
    }

    private static JwtRequestPostProcessor token(String role) {
        return jwt().jwt(j -> j.claim("tid", "11111111-1111-1111-1111-111111111111")
                        .claim("oid", "22222222-2222-2222-2222-222222222222"))
                .authorities(new SimpleGrantedAuthority("SCOPE_Catalog.Read"), new SimpleGrantedAuthority(role));
    }
}
