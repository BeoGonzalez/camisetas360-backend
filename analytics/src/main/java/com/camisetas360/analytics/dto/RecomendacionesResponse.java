package com.camisetas360.analytics.dto;

import com.camisetas360.analytics.model.Recomendacion;

import java.util.List;
import java.util.Map;

/**
 * @param perfil      categorias de mayor interes del usuario con su afinidad (0..1)
 * @param generadoEn  instante del ultimo calculo, o null si aun no hay historial
 */
public record RecomendacionesResponse(List<Recomendacion> items, Map<String, Double> perfil, String generadoEn) {
}
