package com.camisetas360.analytics.controller;

import com.camisetas360.analytics.dto.RecomendacionesResponse;
import com.camisetas360.analytics.security.EntraIdentity;
import com.camisetas360.analytics.service.AnalyticsService;
import com.camisetas360.analytics.service.MetricasService;
import com.camisetas360.analytics.service.MetricasSnapshot;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final MetricasService metricas;

    public AnalyticsController(AnalyticsService analytics, MetricasService metricas) {
        this.analytics = analytics;
        this.metricas = metricas;
    }

    /** Recomendaciones del usuario autenticado (mismo id tenant:object que usan catalog y carrito). */
    @GetMapping("/recomendaciones")
    public RecomendacionesResponse recomendaciones(@AuthenticationPrincipal Jwt jwt) {
        var identity = EntraIdentity.from(jwt);
        String usuarioId = identity.tenantId() + ":" + identity.objectId();
        return analytics.obtener(usuarioId)
                .map(guardadas -> new RecomendacionesResponse(guardadas.items(), guardadas.perfil(), guardadas.generadoEn()))
                .orElseGet(() -> new RecomendacionesResponse(List.of(), Map.of(), null));
    }

    /** Snapshot de metricas, para la carga inicial del dashboard antes de abrir el WebSocket. */
    @GetMapping("/metricas")
    public MetricasSnapshot metricas() {
        return metricas.snapshot();
    }
}
