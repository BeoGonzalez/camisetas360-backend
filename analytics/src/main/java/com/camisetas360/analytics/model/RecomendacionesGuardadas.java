package com.camisetas360.analytics.model;

import java.util.List;
import java.util.Map;

/**
 * Recomendaciones y perfil de navegacion de un usuario.
 * @param perfil afinidad (0..1) por categoria, de mayor a menor.
 */
public record RecomendacionesGuardadas(List<Recomendacion> items, Map<String, Double> perfil, String generadoEn) {
}
