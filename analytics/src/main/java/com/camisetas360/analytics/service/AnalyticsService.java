package com.camisetas360.analytics.service;

import com.camisetas360.analytics.model.InteraccionEvento;
import com.camisetas360.analytics.model.RecomendacionesGuardadas;
import com.camisetas360.analytics.repository.EventoRepository;
import com.camisetas360.analytics.repository.ProductoRepository;
import com.camisetas360.analytics.repository.RecomendacionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Procesa cada interaccion: la guarda en el historial (idempotente), actualiza el producto
 * y las metricas, y recalcula las recomendaciones y el perfil del usuario.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    static final int HISTORIAL_ANALIZADO = 50;

    private final EventoRepository eventos;
    private final ProductoRepository productos;
    private final RecomendacionRepository recomendaciones;
    private final RecomendacionEngine engine;
    private final MetricasService metricas;
    private final int limite;
    private final Clock clock;

    @Autowired
    public AnalyticsService(EventoRepository eventos, ProductoRepository productos,
                            RecomendacionRepository recomendaciones, RecomendacionEngine engine,
                            MetricasService metricas, @Value("${app.recomendaciones.limite:6}") int limite) {
        this(eventos, productos, recomendaciones, engine, metricas, limite, Clock.systemUTC());
    }

    AnalyticsService(EventoRepository eventos, ProductoRepository productos, RecomendacionRepository recomendaciones,
                     RecomendacionEngine engine, MetricasService metricas, int limite, Clock clock) {
        this.eventos = eventos;
        this.productos = productos;
        this.recomendaciones = recomendaciones;
        this.engine = engine;
        this.metricas = metricas;
        this.limite = limite;
        this.clock = clock;
    }

    public void registrar(InteraccionEvento evento) {
        if (!eventos.guardarSiNoExiste(evento)) {
            log.debug("Evento duplicado ignorado eventId={}", evento.eventId());
            return;
        }
        metricas.registrar(evento);
        if (evento.producto() == null) {
            return;
        }
        productos.registrar(evento);
        recalcular(evento.usuarioId());
    }

    public void recalcular(String usuarioId) {
        Instant ahora = clock.instant();
        var recientes = eventos.recientes(usuarioId, HISTORIAL_ANALIZADO);
        var items = engine.recomendar(recientes, productos.catalogo(), ahora, limite);
        recomendaciones.guardar(usuarioId, items, engine.perfil(recientes, ahora), ahora);
    }

    public Optional<RecomendacionesGuardadas> obtener(String usuarioId) {
        return recomendaciones.buscar(usuarioId);
    }
}
