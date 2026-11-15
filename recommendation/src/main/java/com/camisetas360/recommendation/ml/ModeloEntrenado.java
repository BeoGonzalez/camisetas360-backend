package com.camisetas360.recommendation.ml;

import com.camisetas360.recommendation.ml.JdbcDatosModeloRepository.Par;
import com.camisetas360.recommendation.model.ProductoFicha;
import com.camisetas360.recommendation.service.ModeloRecomendaciones;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resultado inmutable de un entrenamiento: el Top-K precalculado de cada usuario (sin productos ya
 * comprados) y los productos populares para usuarios sin historial. Responder una consulta es una
 * busqueda en memoria; TensorFlow solo corre al reentrenar.
 */
public record ModeloEntrenado(String version, boolean conTensorFlow, int usuarios, int productos,
                              Map<String, List<ModeloRecomendaciones.Item>> porUsuario,
                              List<ModeloRecomendaciones.Item> populares,
                              Map<String, Set<String>> comprados) {

    private static final DateTimeFormatter VERSION =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public ModeloRecomendaciones.Resultado recomendar(String usuarioId, int limite) {
        var propias = porUsuario.get(usuarioId);
        if (propias != null && !propias.isEmpty()) {
            return new ModeloRecomendaciones.Resultado(true, version, propias.stream().limit(limite).toList());
        }
        Set<String> yaComprados = comprados.getOrDefault(usuarioId, Set.of());
        return new ModeloRecomendaciones.Resultado(false, version,
                populares.stream().filter(i -> !yaComprados.contains(i.sku())).limit(limite).toList());
    }

    public static ModeloEntrenado construir(List<Par> pares, Map<String, ProductoFicha> fichas, int topK,
                                            FactorizacionTensorFlow.Parametros parametros, Instant ahora) {
        List<String> usuarios = new ArrayList<>(new TreeSet<>(pares.stream().map(Par::usuarioId).toList()));
        List<String> productos = new ArrayList<>(new TreeSet<>(pares.stream().map(Par::sku).toList()));
        Map<String, Set<String>> comprados = new HashMap<>();
        Map<String, Double> popularidad = new HashMap<>();
        for (Par par : pares) {
            popularidad.merge(par.sku(), par.peso(), Double::sum);
            if (par.comprado()) comprados.computeIfAbsent(par.usuarioId(), u -> new HashSet<>()).add(par.sku());
        }
        var populares = populares(popularidad, fichas, topK);
        String version = VERSION.format(ahora);
        Map<String, Set<String>> compradosInmutable = new HashMap<>();
        comprados.forEach((u, s) -> compradosInmutable.put(u, Set.copyOf(s)));

        if (usuarios.size() < 2 || productos.size() < 2) {
            // Sin al menos dos usuarios y dos productos no hay nada que aprender: solo popularidad.
            return new ModeloEntrenado(version, false, usuarios.size(), productos.size(), Map.of(), populares,
                    Map.copyOf(compradosInmutable));
        }

        Map<String, Integer> indiceUsuario = indice(usuarios);
        Map<String, Integer> indiceProducto = indice(productos);
        int[] u = new int[pares.size()];
        int[] i = new int[pares.size()];
        float[] w = new float[pares.size()];
        for (int n = 0; n < pares.size(); n++) {
            u[n] = indiceUsuario.get(pares.get(n).usuarioId());
            i[n] = indiceProducto.get(pares.get(n).sku());
            w[n] = (float) pares.get(n).peso();
        }
        float[][] afinidad = FactorizacionTensorFlow.entrenar(usuarios.size(), productos.size(), u, i, w, parametros);

        Map<String, List<ModeloRecomendaciones.Item>> porUsuario = new HashMap<>();
        for (int a = 0; a < usuarios.size(); a++) {
            String usuario = usuarios.get(a);
            Set<String> yaComprados = comprados.getOrDefault(usuario, Set.of());
            float[] fila = afinidad[a];
            porUsuario.put(usuario, java.util.stream.IntStream.range(0, productos.size()).boxed()
                    .filter(b -> !yaComprados.contains(productos.get(b)))
                    .sorted(Comparator.<Integer>comparingDouble(b -> -fila[b]).thenComparing(productos::get))
                    .limit(topK)
                    .map(b -> item(productos.get(b), fichas, fila[b]))
                    .toList());
        }
        return new ModeloEntrenado(version, true, usuarios.size(), productos.size(), Map.copyOf(porUsuario),
                populares, Map.copyOf(compradosInmutable));
    }

    private static List<ModeloRecomendaciones.Item> populares(Map<String, Double> popularidad,
                                                              Map<String, ProductoFicha> fichas, int topK) {
        double maximo = popularidad.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        return popularidad.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(topK)
                .map(e -> item(e.getKey(), fichas, e.getValue() / maximo))
                .toList();
    }

    private static ModeloRecomendaciones.Item item(String sku, Map<String, ProductoFicha> fichas, double score) {
        ProductoFicha ficha = fichas.get(sku);
        double redondeado = Math.round(score * 10_000.0) / 10_000.0;
        return ficha == null
                ? new ModeloRecomendaciones.Item(sku, null, null, null, redondeado)
                : new ModeloRecomendaciones.Item(sku, ficha.nombre(), ficha.categoria(), ficha.precio(), redondeado);
    }

    private static Map<String, Integer> indice(List<String> valores) {
        Map<String, Integer> indice = new HashMap<>();
        for (int n = 0; n < valores.size(); n++) indice.put(valores.get(n), n);
        return indice;
    }
}
