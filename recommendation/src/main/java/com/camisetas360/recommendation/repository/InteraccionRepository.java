package com.camisetas360.recommendation.repository;

import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.model.ProductoFicha;
import com.camisetas360.recommendation.model.Recomendacion;

import java.util.List;

public interface InteraccionRepository {

    /** Guarda la interaccion; devuelve false si su eventId ya estaba registrado. */
    boolean guardarSiNoExiste(Interaccion interaccion);

    /** Inserta o completa la ficha del producto sin borrar datos conocidos con valores nulos. */
    void actualizarProducto(ProductoFicha producto);

    /**
     * Toma el lock transaccional del usuario. Serializa el registro y el recalculo del Top 5 de un
     * mismo usuario cuando llegan eventos de Kafka y RabbitMQ en paralelo. Debe ser el primer
     * bloqueo de la transaccion.
     */
    void bloquearUsuario(String usuarioId);

    /**
     * Recalcula y reemplaza el Top 5 del usuario a partir de todas sus interacciones.
     * Requiere haber llamado antes a {@link #bloquearUsuario(String)} en la misma transaccion.
     */
    void recalcularTop5(String usuarioId);

    List<Recomendacion> top5(String usuarioId);
}
