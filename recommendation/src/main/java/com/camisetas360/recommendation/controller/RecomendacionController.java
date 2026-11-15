package com.camisetas360.recommendation.controller;

import com.camisetas360.recommendation.security.EntraIdentity;
import com.camisetas360.recommendation.service.RecomendacionesConsulta;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recomendaciones del usuario autenticado. Equivale al GET /recommendations?userId= de las guias,
 * pero el usuario se toma del token (mismo id {@code <tid>:<oid>} que usan catalog, carrito y orders).
 * {@code fuente}: MODELO (TensorFlow), PESOS (Top 5 ponderado) o POPULARES (usuario sin historial).
 */
@RestController
@RequestMapping("/api/v1/recomendaciones")
public class RecomendacionController {

    private final RecomendacionesConsulta consulta;

    public RecomendacionController(RecomendacionesConsulta consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public RecomendacionesResponse top5(@AuthenticationPrincipal Jwt jwt) {
        var identity = EntraIdentity.from(jwt);
        var resultado = consulta.consultar(identity.tenantId() + ":" + identity.objectId());
        return new RecomendacionesResponse(resultado.fuente().name(), resultado.versionModelo(), resultado.items());
    }

    public record RecomendacionesResponse(String fuente, String versionModelo, List<RecomendacionesConsulta.Item> items) {
    }
}
