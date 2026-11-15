package com.camisetas360.analytics.controller;

import com.camisetas360.analytics.model.Recomendacion;
import com.camisetas360.analytics.model.RecomendacionesGuardadas;
import com.camisetas360.analytics.security.SecurityConfig;
import com.camisetas360.analytics.service.AnalyticsService;
import com.camisetas360.analytics.service.MetricasService;
import com.camisetas360.analytics.service.MetricasSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AnalyticsController.class)
@Import(SecurityConfig.class)
class AnalyticsControllerWebMvcTest {

    private static final String USUARIO = "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean AnalyticsService analytics;
    @MockitoBean MetricasService metricas;

    @Test
    void recomendaciones_devuelveLasDelUsuarioAutenticado() throws Exception {
        Map<String, Double> perfil = new LinkedHashMap<>();
        perfil.put("Selecciones", 1.0);
        when(analytics.obtener(USUARIO)).thenReturn(Optional.of(new RecomendacionesGuardadas(
                List.of(new Recomendacion("CHI-VIS", "Chile Visita", "Selecciones", 27990.0, 0.99, "Parecida a Chile Local")),
                perfil, "2026-11-10T12:00:00Z")));

        mvc.perform(get("/api/v1/analytics/recomendaciones").with(token("ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sku").value("CHI-VIS"))
                .andExpect(jsonPath("$.items[0].motivo").value("Parecida a Chile Local"))
                .andExpect(jsonPath("$.perfil.Selecciones").value(1.0))
                .andExpect(jsonPath("$.generadoEn").value("2026-11-10T12:00:00Z"));
    }

    @Test
    void recomendaciones_vaciasSinHistorial() throws Exception {
        when(analytics.obtener(USUARIO)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/analytics/recomendaciones").with(token("ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.generadoEn").doesNotExist());
    }

    @Test
    void metricas_paraAdministradores() throws Exception {
        when(metricas.snapshot()).thenReturn(new MetricasSnapshot("2026-11-10T12:00:00Z", 5, 2, List.of(),
                List.of(new MetricasSnapshot.ProductoVisto("A", "Camiseta A", 3)), Map.of("PRODUCTO_VISTO", 3L), List.of()));

        mvc.perform(get("/api/v1/analytics/metricas").with(token("ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clicsUltimoMinuto").value(5))
                .andExpect(jsonPath("$.productosMasVistos[0].vistas").value(3));
    }

    @Test
    void metricas_prohibidasParaClientes() throws Exception {
        mvc.perform(get("/api/v1/analytics/metricas").with(token("ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        verifyNoInteractions(metricas);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/analytics/recomendaciones", "/api/v1/analytics/metricas"})
    void endpoints_requierenToken(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(analytics, metricas);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/analytics/recomendaciones", "/api/v1/analytics/metricas"})
    void endpoints_requierenScope(String path) throws Exception {
        mvc.perform(get(path).with(jwt().jwt(j -> j.claim("tid", "11111111-1111-1111-1111-111111111111")
                        .claim("oid", "22222222-2222-2222-2222-222222222222"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"), new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(analytics, metricas);
    }

    @Test
    void rutasDesconocidasYMetodosNoPermitidos_seDeniegan() throws Exception {
        mvc.perform(get("/api/v1/analytics/otra").with(token("ROLE_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/analytics/recomendaciones").with(token("ROLE_CUSTOMER"))).andExpect(status().isForbidden());
    }

    private static JwtRequestPostProcessor token(String role) {
        return jwt().jwt(j -> j.claim("tid", "11111111-1111-1111-1111-111111111111")
                        .claim("oid", "22222222-2222-2222-2222-222222222222"))
                .authorities(new SimpleGrantedAuthority("SCOPE_Catalog.Read"), new SimpleGrantedAuthority(role));
    }
}
