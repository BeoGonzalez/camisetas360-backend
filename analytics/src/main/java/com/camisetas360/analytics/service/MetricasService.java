package com.camisetas360.analytics.service;

import com.camisetas360.analytics.model.AlertaFraude;
import com.camisetas360.analytics.model.InteraccionEvento;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Agregados en memoria de la ultima hora de interacciones. Pensado para una tarea ECS:
 * con varias tareas cada una veria solo su parte del trafico (las particiones que consume).
 */
@Service
public class MetricasService {

    private static final Duration VENTANA = Duration.ofHours(1);
    private static final Duration VENTANA_NAVEGACION = Duration.ofSeconds(10);
    private static final int MAX_REGISTROS = 200_000;
    private static final int MAX_ALERTAS = 20;

    private record Registro(Instant at, String tipo, String usuarioId, String sku, String nombre) {
    }

    private final Clock clock;
    private final int umbralNavegacion;
    private final int umbralPico;
    private final Deque<Registro> registros = new ArrayDeque<>();
    private final Deque<MetricasSnapshot.Alerta> alertas = new ArrayDeque<>();
    private final Map<String, Instant> ultimaAlertaUsuario = new HashMap<>();
    private Instant ultimaAlertaPico = Instant.EPOCH;

    @Autowired
    public MetricasService(@Value("${app.metricas.umbral-navegacion:20}") int umbralNavegacion,
                           @Value("${app.metricas.umbral-pico:60}") int umbralPico) {
        this(Clock.systemUTC(), umbralNavegacion, umbralPico);
    }

    MetricasService(Clock clock, int umbralNavegacion, int umbralPico) {
        this.clock = clock;
        this.umbralNavegacion = umbralNavegacion;
        this.umbralPico = umbralPico;
    }

    public synchronized void registrar(InteraccionEvento evento) {
        Instant ahora = clock.instant();
        purgar(ahora);
        String sku = evento.producto() == null ? null : evento.producto().sku();
        String nombre = evento.producto() == null ? null : evento.producto().nombre();
        registros.addLast(new Registro(ahora, evento.eventType(), evento.usuarioId(), sku, nombre));
        while (registros.size() > MAX_REGISTROS) {
            registros.removeFirst();
        }
        detectarNavegacionAnomala(evento.usuarioId(), ahora);
        detectarPico(ahora);
    }

    public synchronized void registrarFraude(AlertaFraude fraude) {
        String tarjeta = fraude.ultimos4() == null ? "desconocida" : "****" + fraude.ultimos4();
        String orden = fraude.ordenId() == null ? "" : ", orden " + fraude.ordenId();
        agregarAlerta("FRAUDE", "Pago bloqueado por antifraude (tarjeta " + tarjeta + orden + ")", clock.instant());
    }

    public synchronized MetricasSnapshot snapshot() {
        Instant ahora = clock.instant();
        purgar(ahora);

        Instant haceUnMinuto = ahora.minusSeconds(60);
        Instant haceCincoMinutos = ahora.minus(Duration.ofMinutes(5));
        Instant minutoActual = ahora.truncatedTo(ChronoUnit.MINUTES);
        Instant inicioSerie = minutoActual.minus(Duration.ofMinutes(14));

        long ultimoMinuto = 0;
        Set<String> activos = new HashSet<>();
        Map<Instant, Long> porMinuto = new TreeMap<>();
        for (int i = 0; i < 15; i++) {
            porMinuto.put(inicioSerie.plus(Duration.ofMinutes(i)), 0L);
        }
        Map<String, Long> porTipo = new TreeMap<>();
        Map<String, Long> vistas = new HashMap<>();
        Map<String, String> nombres = new HashMap<>();

        for (Registro registro : registros) {
            if (registro.at().isAfter(haceUnMinuto)) {
                ultimoMinuto++;
            }
            if (registro.at().isAfter(haceCincoMinutos) && registro.usuarioId() != null) {
                activos.add(registro.usuarioId());
            }
            Instant minuto = registro.at().truncatedTo(ChronoUnit.MINUTES);
            if (!minuto.isBefore(inicioSerie)) {
                porMinuto.merge(minuto, 1L, Long::sum);
            }
            porTipo.merge(registro.tipo(), 1L, Long::sum);
            if (InteraccionEvento.PRODUCTO_VISTO.equals(registro.tipo()) && registro.sku() != null) {
                vistas.merge(registro.sku(), 1L, Long::sum);
                if (registro.nombre() != null) {
                    nombres.put(registro.sku(), registro.nombre());
                }
            }
        }

        List<MetricasSnapshot.PuntoSerie> serie = new ArrayList<>();
        porMinuto.forEach((minuto, clics) -> serie.add(new MetricasSnapshot.PuntoSerie(minuto.toString(), clics)));

        List<MetricasSnapshot.ProductoVisto> top = vistas.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .limit(5)
                .map(entry -> new MetricasSnapshot.ProductoVisto(entry.getKey(),
                        nombres.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue()))
                .toList();

        return new MetricasSnapshot(ahora.toString(), ultimoMinuto, activos.size(), List.copyOf(serie), top,
                new LinkedHashMap<>(porTipo), List.copyOf(alertas));
    }

    private void detectarNavegacionAnomala(String usuarioId, Instant ahora) {
        if (usuarioId == null) {
            return;
        }
        Instant desde = ahora.minus(VENTANA_NAVEGACION);
        long recientes = 0;
        Iterator<Registro> it = registros.descendingIterator();
        while (it.hasNext()) {
            Registro registro = it.next();
            if (registro.at().isBefore(desde)) {
                break;
            }
            if (usuarioId.equals(registro.usuarioId())) {
                recientes++;
            }
        }
        Instant anterior = ultimaAlertaUsuario.get(usuarioId);
        if (recientes > umbralNavegacion && (anterior == null || anterior.isBefore(ahora.minusSeconds(60)))) {
            ultimaAlertaUsuario.put(usuarioId, ahora);
            agregarAlerta("NAVEGACION_ANOMALA", "Usuario " + enmascarar(usuarioId) + " genero " + recientes
                    + " interacciones en " + VENTANA_NAVEGACION.toSeconds() + " segundos", ahora);
        }
    }

    private void detectarPico(Instant ahora) {
        Instant haceUnMinuto = ahora.minusSeconds(60);
        long ultimoMinuto = 0;
        Iterator<Registro> it = registros.descendingIterator();
        while (it.hasNext() && !it.next().at().isBefore(haceUnMinuto)) {
            ultimoMinuto++;
        }
        if (ultimoMinuto > umbralPico && ultimaAlertaPico.isBefore(ahora.minusSeconds(60))) {
            ultimaAlertaPico = ahora;
            agregarAlerta("PICO_TRAFICO", "Pico de trafico: " + ultimoMinuto + " interacciones en el ultimo minuto", ahora);
        }
    }

    private void agregarAlerta(String tipo, String mensaje, Instant ahora) {
        alertas.addFirst(new MetricasSnapshot.Alerta(tipo, mensaje, ahora.toString()));
        while (alertas.size() > MAX_ALERTAS) {
            alertas.removeLast();
        }
    }

    private void purgar(Instant ahora) {
        Instant limite = ahora.minus(VENTANA);
        while (!registros.isEmpty() && registros.peekFirst().at().isBefore(limite)) {
            registros.removeFirst();
        }
        ultimaAlertaUsuario.values().removeIf(instante -> instante.isBefore(ahora.minusSeconds(120)));
    }

    /** Nunca se muestra el identificador completo del usuario en el dashboard. */
    private static String enmascarar(String usuarioId) {
        String sufijo = usuarioId.length() <= 4 ? usuarioId : usuarioId.substring(usuarioId.length() - 4);
        return "..." + sufijo;
    }
}
