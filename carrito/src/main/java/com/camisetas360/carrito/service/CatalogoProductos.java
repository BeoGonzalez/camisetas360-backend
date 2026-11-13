package com.camisetas360.carrito.service;

import java.util.Collection;
import java.util.Map;

/**
 * Puerto hacia el catalogo: fuente de verdad de nombre, categoria y precio.
 * El carrito nunca confia en el precio que envia el cliente.
 */
public interface CatalogoProductos {

    /**
     * Productos vigentes para los SKU pedidos. Los SKU inexistentes no aparecen en el mapa.
     *
     * @throws CatalogoNoDisponibleException si el catalogo no responde o falla
     */
    Map<String, ProductoCatalogo> buscar(Collection<String> skus);
}
