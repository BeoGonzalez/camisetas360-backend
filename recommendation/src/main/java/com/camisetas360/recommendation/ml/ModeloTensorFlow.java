package com.camisetas360.recommendation.ml;

import com.camisetas360.recommendation.service.ModeloRecomendaciones;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modelo de TensorFlow dentro del microservicio. Se reentrena periodicamente con todas las
 * interacciones (reemplaza el model.fit por evento de la guia) y publica un {@link ModeloEntrenado}
 * nuevo con una sola asignacion atomica: las consultas ven el modelo anterior o el nuevo, nunca uno
 * a medio entrenar. Si un entrenamiento falla se conserva el anterior.
 */
@Component
@ConditionalOnProperty(name = "app.tensorflow.enabled", havingValue = "true", matchIfMissing = true)
public class ModeloTensorFlow implements ModeloRecomendaciones {

    private static final Logger log = LoggerFactory.getLogger(ModeloTensorFlow.class);

    private final JdbcDatosModeloRepository datos;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final FactorizacionTensorFlow.Parametros parametros;
    private final int topK;
    private final int maximoPares;
    private final AtomicReference<ModeloEntrenado> vigente = new AtomicReference<>();

    public ModeloTensorFlow(JdbcDatosModeloRepository datos, Clock clock, MeterRegistry metrics,
                            @Value("${app.tensorflow.dimension:16}") int dimension,
                            @Value("${app.tensorflow.epocas:40}") int epocas,
                            @Value("${app.tensorflow.negativos-por-positivo:4}") int negativos,
                            @Value("${app.tensorflow.top-k:20}") int topK,
                            @Value("${app.tensorflow.maximo-pares:200000}") int maximoPares) {
        this.datos = Objects.requireNonNull(datos, "datos");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        var defecto = FactorizacionTensorFlow.Parametros.porDefecto();
        this.parametros = new FactorizacionTensorFlow.Parametros(dimension, epocas, negativos,
                defecto.tasaAprendizaje(), defecto.l2(), defecto.semilla());
        this.topK = topK;
        this.maximoPares = maximoPares;
    }

    @Override
    public Optional<Resultado> recomendar(String usuarioId, int limite) {
        return Optional.ofNullable(vigente.get()).map(modelo -> modelo.recomendar(usuarioId, limite));
    }

    public Optional<ModeloEntrenado> vigente() {
        return Optional.ofNullable(vigente.get());
    }

    @Scheduled(initialDelayString = "${app.tensorflow.retraso-inicial-ms:15000}",
            fixedDelayString = "${app.tensorflow.reentrenar-cada-ms:300000}")
    public synchronized void reentrenar() {
        long inicio = System.nanoTime();
        try {
            var pares = datos.pares(maximoPares);
            var modelo = ModeloEntrenado.construir(pares, datos.productos(), topK, parametros, clock.instant());
            vigente.set(modelo);
            metrics.counter("recommendation.tensorflow.entrenamientos", "result", "ok").increment();
            log.info("Modelo TensorFlow {} listo: {} pares, {} usuarios, {} productos, tensorflow={} en {} ms",
                    modelo.version(), pares.size(), modelo.usuarios(), modelo.productos(), modelo.conTensorFlow(),
                    (System.nanoTime() - inicio) / 1_000_000);
        } catch (RuntimeException | LinkageError fallo) {
            // LinkageError: binarios nativos de TensorFlow ausentes. El servicio sigue con el Top 5 ponderado.
            metrics.counter("recommendation.tensorflow.entrenamientos", "result", "error").increment();
            log.error("Reentrenamiento TensorFlow fallido; se mantiene el modelo anterior", fallo);
        }
    }
}
