package com.camisetas360.pago.adapter;

import com.camisetas360.pago.port.FraudeDetector;
import com.camisetas360.pago.port.ResultadoEvaluacionFraude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementación de ventana deslizante en memoria para detección de fraude.
 *
 * Justificación de diseño:
 * Esta implementación utiliza ConcurrentHashMap y bloqueos atómicos por clave (compute)
 * con Deque de marcas de tiempo en milisegundos. Evita agregar una dependencia externa como
 * Redis en el stack local y para despliegues de una sola réplica, garantizando atomicidad
 * thread-safe completa ante concurrencia.
 *
 * Límites en escenario con varias réplicas:
 * Cada réplica mantendrá su propio estado en memoria independiente, por lo que un atacante
 * que distribuya intentos entre distintas réplicas detrás de un balanceador de carga
 * podría evadir el umbral por réplica. En producción con escalamiento horizontal, se debe
 * sustituir este adaptador por uno basado en Redis (utilizando sorted sets ZADD/ZREMRANGEBYSCORE/ZCARD
 * atómicos mediante script Lua) implementando la misma interfaz FraudeDetector.
 */
@Component
public class InMemorySlidingWindowFraudeDetector implements FraudeDetector {

    private static final Logger log = LoggerFactory.getLogger(InMemorySlidingWindowFraudeDetector.class);

    private final ConcurrentHashMap<String, Deque<Long>> windowMap = new ConcurrentHashMap<>();
    private final int maxIntentos;
    private final int ventanaSegundos;

    public InMemorySlidingWindowFraudeDetector(
            @Value("${antifraude.max-intentos:3}") int maxIntentos,
            @Value("${antifraude.ventana-segundos:10}") int ventanaSegundos
    ) {
        this.maxIntentos = maxIntentos;
        this.ventanaSegundos = ventanaSegundos;
    }

    @Override
    public ResultadoEvaluacionFraude evaluarTransaccion(String tarjetaHash, UUID ordenId) {
        long now = System.currentTimeMillis();
        long cutoff = now - (ventanaSegundos * 1000L);

        // compute garantiza ejecución atómica para la clave dada
        int[] intentosHolder = new int[1];

        windowMap.compute(tarjetaHash, (key, queue) -> {
            if (queue == null) {
                queue = new ArrayDeque<>();
            }
            // Limpiar timestamps que han salido de la ventana deslizante
            while (!queue.isEmpty() && queue.peekFirst() <= cutoff) {
                queue.pollFirst();
            }
            // Registrar el intento actual
            queue.addLast(now);
            intentosHolder[0] = queue.size();
            return queue;
        });

        int intentosActuales = intentosHolder[0];

        // Semántica explícita: "más de 3" -> 1, 2, 3 pasan; el 4.º intento dentro de 10s se bloquea
        if (intentosActuales > maxIntentos) {
            String motivo = String.format(
                    "Bloqueado preventivamente por antifraude: %d intentos en ventana de %d segundos (máximo permitido: %d)",
                    intentosActuales, ventanaSegundos, maxIntentos
            );
            log.warn("ALERTA ANTIFRAUDE detectada para hash {}... en orden {}: {}",
                    tarjetaHash.substring(0, Math.min(8, tarjetaHash.length())), ordenId, motivo);
            return new ResultadoEvaluacionFraude(false, intentosActuales, ventanaSegundos, motivo);
        }

        return new ResultadoEvaluacionFraude(true, intentosActuales, ventanaSegundos, "OK");
    }

    @Override
    public void reset() {
        windowMap.clear();
    }

    /**
     * Limpieza periódica cada minuto de claves cuyas marcas de tiempo han expirado
     * para prevenir fugas de memoria con tarjetas inactivas.
     */
    @Scheduled(fixedDelay = 60000)
    public void limpiarEntradasExpiradas() {
        long cutoff = System.currentTimeMillis() - (ventanaSegundos * 1000L);
        windowMap.entrySet().removeIf(entry -> {
            Deque<Long> queue = entry.getValue();
            synchronized (queue) {
                while (!queue.isEmpty() && queue.peekFirst() <= cutoff) {
                    queue.pollFirst();
                }
                return queue.isEmpty();
            }
        });
    }

    public int getMaxIntentos() {
        return maxIntentos;
    }

    public int getVentanaSegundos() {
        return ventanaSegundos;
    }
}
