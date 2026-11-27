package com.camisetas360.pago.adapter;

import com.camisetas360.pago.port.FraudeDetector;
import com.camisetas360.pago.port.ResultadoEvaluacionFraude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementación de ventana deslizante en memoria para detección de fraude, con bloqueo temporal.
 *
 * Reglas:
 * - Se cuentan los intentos de cada tarjeta (por su hash) en los últimos {@code ventanaSegundos}.
 * - Si superan {@code maxIntentos}, la tarjeta queda BLOQUEADA durante {@code bloqueoMinutos}
 *   (30 por defecto), contados desde el intento que disparó el bloqueo.
 * - Los intentos durante el bloqueo se rechazan pero NO lo alargan: la tarjeta se desbloquea
 *   exactamente a la hora informada, y entonces empieza con la ventana vacía.
 *
 * Justificación de diseño:
 * Esta implementación utiliza ConcurrentHashMap y bloqueos atómicos por clave (compute)
 * con Deque de marcas de tiempo en milisegundos. Evita agregar una dependencia externa como
 * Redis en el stack local y para despliegues de una sola réplica, garantizando atomicidad
 * thread-safe completa ante concurrencia.
 *
 * Límites en escenario con varias réplicas:
 * Cada réplica mantendrá su propio estado en memoria independiente (intentos y bloqueos), por lo
 * que un atacante que distribuya intentos entre distintas réplicas detrás de un balanceador de carga
 * podría evadir el umbral por réplica, y un reinicio de la tarea levanta los bloqueos vigentes.
 * En producción con escalamiento horizontal, se debe sustituir este adaptador por uno basado en
 * Redis (sorted sets ZADD/ZREMRANGEBYSCORE/ZCARD atómicos mediante script Lua, y una clave con TTL
 * para el bloqueo) implementando la misma interfaz FraudeDetector.
 */
// @Primary: el detector de tarjetas es el que se inyecta por defecto; el de Webpay se pide con @Qualifier.
@Primary
@Component
public class InMemorySlidingWindowFraudeDetector implements FraudeDetector {

    private static final Logger log = LoggerFactory.getLogger(InMemorySlidingWindowFraudeDetector.class);

    /** Intentos recientes y fin del bloqueo (epoch ms; 0 = sin bloqueo) de una tarjeta. */
    private static final class EstadoTarjeta {
        private final Deque<Long> intentos = new ArrayDeque<>();
        private long bloqueadaHasta;
    }

    private final ConcurrentHashMap<String, EstadoTarjeta> estados = new ConcurrentHashMap<>();
    private final int maxIntentos;
    private final int ventanaSegundos;
    private final int bloqueoMinutos;

    private final Clock clock;

    @Autowired
    public InMemorySlidingWindowFraudeDetector(
            @Value("${antifraude.max-intentos:2}") int maxIntentos,
            @Value("${antifraude.ventana-segundos:10}") int ventanaSegundos,
            @Value("${antifraude.bloqueo-minutos:30}") int bloqueoMinutos
    ) {
        this(maxIntentos, ventanaSegundos, bloqueoMinutos, Clock.systemUTC());
    }

    /** Reloj inyectable para probar la ventana y el bloqueo sin depender del tiempo real. */
    InMemorySlidingWindowFraudeDetector(int maxIntentos, int ventanaSegundos, int bloqueoMinutos, Clock clock) {
        if (bloqueoMinutos < 1) {
            throw new IllegalArgumentException("antifraude.bloqueo-minutos debe ser al menos 1");
        }
        this.maxIntentos = maxIntentos;
        this.ventanaSegundos = ventanaSegundos;
        this.bloqueoMinutos = bloqueoMinutos;
        this.clock = clock;
    }

    @Override
    public ResultadoEvaluacionFraude evaluarTransaccion(String tarjetaHash, UUID ordenId) {
        long now = clock.millis();
        long cutoff = now - (ventanaSegundos * 1000L);

        // compute garantiza ejecución atómica para la clave dada
        int[] intentosHolder = new int[1];
        long[] bloqueadaHastaHolder = new long[1];
        boolean[] bloqueoNuevo = new boolean[1];

        estados.compute(tarjetaHash, (key, estado) -> {
            if (estado == null) {
                estado = new EstadoTarjeta();
            }
            if (estado.bloqueadaHasta > now) {
                // Sigue bloqueada: se rechaza sin registrar el intento, para no alargar el bloqueo.
                intentosHolder[0] = estado.intentos.size();
                bloqueadaHastaHolder[0] = estado.bloqueadaHasta;
                return estado;
            }
            if (estado.bloqueadaHasta != 0) {
                // El bloqueo vencio: la tarjeta empieza de cero.
                estado.bloqueadaHasta = 0;
                estado.intentos.clear();
            }
            // Limpiar timestamps que han salido de la ventana deslizante
            while (!estado.intentos.isEmpty() && estado.intentos.peekFirst() <= cutoff) {
                estado.intentos.pollFirst();
            }
            // Registrar el intento actual
            estado.intentos.addLast(now);
            intentosHolder[0] = estado.intentos.size();
            // Semántica explícita: "más de maxIntentos" -> con 2 (defecto), 1.º y 2.º pasan; el 3.º dentro de la ventana bloquea
            if (estado.intentos.size() > maxIntentos) {
                estado.bloqueadaHasta = now + bloqueoMinutos * 60_000L;
                bloqueoNuevo[0] = true;
            }
            bloqueadaHastaHolder[0] = estado.bloqueadaHasta;
            return estado;
        });

        int intentosActuales = intentosHolder[0];
        long bloqueadaHasta = bloqueadaHastaHolder[0];

        if (bloqueadaHasta > now) {
            long segundosRestantes = (bloqueadaHasta - now + 999) / 1000;
            String motivo = bloqueoNuevo[0]
                    ? String.format(
                            "Bloqueado preventivamente por antifraude: %d intentos en ventana de %d segundos "
                                    + "(máximo permitido: %d). Tarjeta bloqueada %d minutos, hasta %s",
                            intentosActuales, ventanaSegundos, maxIntentos, bloqueoMinutos,
                            Instant.ofEpochMilli(bloqueadaHasta))
                    : String.format(
                            "Tarjeta bloqueada por antifraude hasta %s (faltan %d segundos)",
                            Instant.ofEpochMilli(bloqueadaHasta), segundosRestantes);
            log.warn("ALERTA ANTIFRAUDE para hash {}... en orden {}: {}",
                    tarjetaHash.substring(0, Math.min(8, tarjetaHash.length())), ordenId, motivo);
            return new ResultadoEvaluacionFraude(false, intentosActuales, ventanaSegundos, motivo, segundosRestantes);
        }

        return new ResultadoEvaluacionFraude(true, intentosActuales, ventanaSegundos, "OK");
    }

    @Override
    public void reset() {
        estados.clear();
    }

    /**
     * Limpieza periódica cada minuto de tarjetas sin intentos vigentes ni bloqueo activo,
     * para prevenir fugas de memoria con tarjetas inactivas.
     */
    @Scheduled(fixedDelay = 60000)
    public void limpiarEntradasExpiradas() {
        long now = clock.millis();
        long cutoff = now - (ventanaSegundos * 1000L);
        // computeIfPresent usa el mismo bloqueo por clave que evaluarTransaccion (compute): la limpieza
        // nunca modifica un estado mientras otro hilo registra un intento en el.
        for (String tarjetaHash : estados.keySet()) {
            estados.computeIfPresent(tarjetaHash, (key, estado) -> {
                if (estado.bloqueadaHasta > now) {
                    return estado;
                }
                if (estado.bloqueadaHasta != 0) {
                    estado.bloqueadaHasta = 0;
                    estado.intentos.clear();
                }
                while (!estado.intentos.isEmpty() && estado.intentos.peekFirst() <= cutoff) {
                    estado.intentos.pollFirst();
                }
                return estado.intentos.isEmpty() ? null : estado;
            });
        }
    }

    public int getMaxIntentos() {
        return maxIntentos;
    }

    public int getVentanaSegundos() {
        return ventanaSegundos;
    }

    public int getBloqueoMinutos() {
        return bloqueoMinutos;
    }
}
