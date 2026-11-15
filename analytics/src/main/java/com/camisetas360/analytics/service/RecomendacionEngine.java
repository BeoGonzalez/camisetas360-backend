package com.camisetas360.analytics.service;

import com.camisetas360.analytics.model.InteraccionEvento;
import com.camisetas360.analytics.model.ProductoInfo;
import com.camisetas360.analytics.model.Recomendacion;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recomendaciones basadas en contenido a partir del historial reciente de navegacion.
 *
 * Cada interaccion aporta interes a un producto y a su categoria, con mas peso si el producto
 * se agrego al carrito y peso negativo si se elimino. El interes decae a la mitad cada 24 horas.
 * Un candidato puntua por: afinidad con su categoria (60%), parecido del nombre con productos
 * de interes, p. ej. mismo club o seleccion (30%), y cercania al precio habitual del usuario (10%).
 * No se recomiendan productos con los que el usuario ya interactuo.
 */
@Component
public class RecomendacionEngine {

    static final double PESO_CATEGORIA = 0.6;
    static final double PESO_NOMBRE = 0.3;
    static final double PESO_PRECIO = 0.1;
    private static final double VIDA_MEDIA_HORAS = 24.0;
    private static final Set<String> STOPWORDS = Set.of(
            "camiseta", "camisetas", "polera", "poleras", "jersey", "del", "las", "los", "con", "para",
            "temporada", "oficial", "version",
            // Variantes genericas de equipacion: no indican que dos camisetas sean del mismo equipo.
            "local", "visita", "visitante", "alternativa", "tercera", "home", "away");

    public List<Recomendacion> recomendar(List<InteraccionEvento> recientes, Collection<ProductoInfo> catalogo,
                                          Instant ahora, int limite) {
        Map<String, Double> interesPorSku = new HashMap<>();
        Map<String, InteraccionEvento.ProductoSnapshot> productosVistos = new HashMap<>();
        Map<String, Double> afinidadCategoria = afinidadPorCategoria(recientes, ahora);
        Set<String> conInteraccion = new HashSet<>();

        for (InteraccionEvento evento : recientes) {
            if (evento.producto() == null || evento.producto().sku() == null) {
                continue;
            }
            String sku = evento.producto().sku();
            conInteraccion.add(sku);
            productosVistos.put(sku, evento.producto());
            interesPorSku.merge(sku, pesoConDecaimiento(evento, ahora), Double::sum);
        }

        List<Map.Entry<String, Double>> positivos = interesPorSku.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .toList();
        double maxAfinidad = afinidadCategoria.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);

        if (positivos.isEmpty() && maxAfinidad <= 0) {
            return populares(catalogo, conInteraccion, limite);
        }

        double maxInteres = positivos.stream().mapToDouble(Map.Entry::getValue).max().orElse(0);
        double precioHabitual = precioPromedioPonderado(positivos, productosVistos);

        List<Recomendacion> resultado = new ArrayList<>();
        for (ProductoInfo candidato : catalogo) {
            if (candidato.sku() == null || conInteraccion.contains(candidato.sku())) {
                continue;
            }

            double categoria = 0;
            if (candidato.categoria() != null && maxAfinidad > 0) {
                categoria = Math.max(0, afinidadCategoria.getOrDefault(clave(candidato.categoria()), 0.0)) / maxAfinidad;
            }

            double nombre = 0;
            String parecidoA = null;
            Set<String> tokensCandidato = tokens(candidato.nombre());
            for (Map.Entry<String, Double> interes : positivos) {
                var visto = productosVistos.get(interes.getKey());
                double similitud = jaccard(tokensCandidato, tokens(visto.nombre())) * (interes.getValue() / maxInteres);
                if (similitud > nombre) {
                    nombre = similitud;
                    parecidoA = visto.nombre();
                }
            }

            if (categoria <= 0 && nombre <= 0) {
                continue;
            }

            double precio = similitudPrecio(candidato.precio(), precioHabitual);
            double score = PESO_CATEGORIA * categoria + PESO_NOMBRE * nombre + PESO_PRECIO * precio;
            String motivo = PESO_CATEGORIA * categoria >= PESO_NOMBRE * nombre
                    ? "Porque te interesan camisetas de " + candidato.categoria()
                    : "Parecida a " + parecidoA;
            resultado.add(new Recomendacion(candidato.sku(), candidato.nombre(), candidato.categoria(),
                    candidato.precio(), redondear(score), motivo));
        }

        resultado.sort(Comparator.comparingDouble(Recomendacion::score).reversed()
                .thenComparing(Recomendacion::sku));
        return List.copyOf(resultado.subList(0, Math.min(limite, resultado.size())));
    }

    /** Perfil de navegacion: afinidad normalizada (0..1) de las 5 categorias mas relevantes. */
    public Map<String, Double> perfil(List<InteraccionEvento> recientes, Instant ahora) {
        Map<String, Double> afinidad = new HashMap<>();
        Map<String, String> nombres = new HashMap<>();
        for (InteraccionEvento evento : recientes) {
            if (evento.producto() != null && evento.producto().categoria() != null) {
                String clave = clave(evento.producto().categoria());
                nombres.putIfAbsent(clave, evento.producto().categoria());
                afinidad.merge(clave, pesoConDecaimiento(evento, ahora), Double::sum);
            }
        }
        double max = afinidad.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        Map<String, Double> perfil = new LinkedHashMap<>();
        if (max <= 0) {
            return perfil;
        }
        afinidad.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(5)
                .forEach(entry -> perfil.put(nombres.get(entry.getKey()), redondear(entry.getValue() / max)));
        return perfil;
    }

    private Map<String, Double> afinidadPorCategoria(List<InteraccionEvento> recientes, Instant ahora) {
        Map<String, Double> afinidad = new HashMap<>();
        for (InteraccionEvento evento : recientes) {
            if (evento.producto() != null && evento.producto().categoria() != null) {
                afinidad.merge(clave(evento.producto().categoria()), pesoConDecaimiento(evento, ahora), Double::sum);
            }
        }
        return afinidad;
    }

    private static List<Recomendacion> populares(Collection<ProductoInfo> catalogo, Set<String> excluir, int limite) {
        long maxVistas = catalogo.stream().mapToLong(ProductoInfo::vistas).max().orElse(0);
        if (maxVistas <= 0) {
            return List.of();
        }
        return catalogo.stream()
                .filter(producto -> producto.sku() != null && !excluir.contains(producto.sku()) && producto.vistas() > 0)
                .sorted(Comparator.comparingLong(ProductoInfo::vistas).reversed().thenComparing(ProductoInfo::sku))
                .limit(limite)
                .map(producto -> new Recomendacion(producto.sku(), producto.nombre(), producto.categoria(),
                        producto.precio(), redondear((double) producto.vistas() / maxVistas), "Popular en la tienda"))
                .toList();
    }

    static double pesoBase(String tipo) {
        return switch (tipo) {
            case InteraccionEvento.PRODUCTO_VISTO -> 1.0;
            case InteraccionEvento.ITEM_AGREGADO -> 3.0;
            case InteraccionEvento.ITEM_ELIMINADO -> -1.5;
            default -> 0.0;
        };
    }

    private static double pesoConDecaimiento(InteraccionEvento evento, Instant ahora) {
        double horas = 0;
        try {
            horas = Math.max(0, Duration.between(Instant.parse(evento.occurredAt()), ahora).toMinutes() / 60.0);
        } catch (DateTimeParseException | NullPointerException ignored) {
            // Evento sin fecha valida: se trata como reciente.
        }
        return pesoBase(evento.eventType()) * Math.pow(0.5, horas / VIDA_MEDIA_HORAS);
    }

    private static double precioPromedioPonderado(List<Map.Entry<String, Double>> positivos,
                                                  Map<String, InteraccionEvento.ProductoSnapshot> productos) {
        double suma = 0;
        double pesos = 0;
        for (Map.Entry<String, Double> interes : positivos) {
            Double precio = productos.get(interes.getKey()).precio();
            if (precio != null && precio > 0) {
                suma += precio * interes.getValue();
                pesos += interes.getValue();
            }
        }
        return pesos > 0 ? suma / pesos : 0;
    }

    private static double similitudPrecio(Double precio, double habitual) {
        if (precio == null || precio <= 0 || habitual <= 0) {
            return 0;
        }
        return Math.max(0, 1 - Math.abs(precio - habitual) / Math.max(precio, habitual));
    }

    static Set<String> tokens(String texto) {
        Set<String> tokens = new HashSet<>();
        if (texto == null) {
            return tokens;
        }
        String normalizado = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        for (String token : normalizado.split("[^a-z0-9]+")) {
            if (token.length() >= 3 && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> interseccion = new HashSet<>(a);
        interseccion.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) interseccion.size() / union.size();
    }

    private static String clave(String categoria) {
        return categoria.trim().toLowerCase(Locale.ROOT);
    }

    private static double redondear(double valor) {
        return Math.round(valor * 1000.0) / 1000.0;
    }
}
