package com.camisetas360.recommendation.service;

import java.util.List;
import java.util.Optional;

/**
 * Modelo de recomendaciones de TensorFlow (ModeloTensorFlow). Nunca lanza excepciones: si aun no
 * hay un modelo entrenado devuelve vacio y quien llama usa el Top 5 ponderado.
 */
public interface ModeloRecomendaciones {

    Optional<Resultado> recomendar(String usuarioId, int limite);

    /** @param personalizado true si el modelo conoce al usuario; false si son solo productos populares */
    record Resultado(boolean personalizado, String version, List<Item> items) {
        public Resultado {
            items = List.copyOf(items);
        }
    }

    record Item(String sku, String nombre, String categoria, Double precio, double score) {
    }
}
