package com.camisetas360.recommendation.messaging;

/** Topicos de interaccion que alimentan el Top 5 (los crea kafka-init; auto-create esta desactivado). */
public final class KafkaTopics {

    public static final String PRODUCTO_VISTO = "catalogo.producto-visto";
    public static final String ITEM_AGREGADO = "carrito.item-agregado";

    /**
     * Interacciones que no se pudieron registrar tras los reintentos (base caida, datos que la base
     * rechaza). Conserva clave y payload originales para reprocesarlas.
     */
    public static final String INTERACCIONES_DLT = "recommendation.interacciones.dlt";

    /** Grupo propio: recommendation recibe todos los eventos sin competir con analytics. */
    public static final String GRUPO = "recommendation-interacciones";

    private KafkaTopics() {
    }
}
