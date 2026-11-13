package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.Recomendacion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Combina las dos guias: el modelo de TensorFlow cuando conoce al usuario y, si no, el Top 5 por
 * peso acumulado (siempre disponible, calculado en PostgreSQL). Los populares del modelo solo se
 * usan para usuarios sin ningun historial.
 */
@Service
public class RecomendacionesConsulta {

    public static final int LIMITE = 5;

    public enum Fuente { MODELO, PESOS, POPULARES }

    /** puntaje: peso acumulado (Top 5 ponderado). score: afinidad 0..1 (modelo). */
    public record Item(int posicion, String sku, Integer puntaje, Double score, String nombre, String categoria,
                       Double precio) {
    }

    public record Resultado(Fuente fuente, String versionModelo, List<Item> items) {
    }

    private final RecomendacionService ponderadas;
    private final Optional<ModeloRecomendaciones> modelo;

    public RecomendacionesConsulta(RecomendacionService ponderadas, Optional<ModeloRecomendaciones> modelo) {
        this.ponderadas = Objects.requireNonNull(ponderadas, "ponderadas");
        this.modelo = Objects.requireNonNull(modelo, "modelo");
    }

    public Resultado consultar(String usuarioId) {
        var delModelo = modelo.flatMap(m -> m.recomendar(usuarioId, LIMITE)).filter(r -> !r.items().isEmpty());
        if (delModelo.isPresent() && delModelo.get().personalizado()) {
            return desdeModelo(Fuente.MODELO, delModelo.get());
        }
        List<Recomendacion> top5 = ponderadas.top5(usuarioId);
        if (top5.isEmpty() && delModelo.isPresent()) {
            return desdeModelo(Fuente.POPULARES, delModelo.get());
        }
        return new Resultado(Fuente.PESOS, null, top5.stream()
                .map(r -> new Item(r.posicion(), r.sku(), r.puntaje(), null, r.nombre(), r.categoria(), r.precio()))
                .toList());
    }

    private static Resultado desdeModelo(Fuente fuente, ModeloRecomendaciones.Resultado resultado) {
        List<Item> items = new ArrayList<>();
        int posicion = 1;
        for (var item : resultado.items()) {
            if (items.size() == LIMITE) break;
            items.add(new Item(posicion++, item.sku(), null, item.score(), item.nombre(), item.categoria(), item.precio()));
        }
        return new Resultado(fuente, resultado.version(), List.copyOf(items));
    }
}
