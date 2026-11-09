package com.camisetas360.pago.adapter;

import com.camisetas360.pago.port.ResultadoEvaluacionFraude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySlidingWindowFraudeDetectorTest {

    private InMemorySlidingWindowFraudeDetector detector;
    private static final String TARJETA_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @BeforeEach
    void setUp() {
        // Umbral: 3 intentos, ventana: 2 segundos para pruebas rápidas
        detector = new InMemorySlidingWindowFraudeDetector(3, 2);
    }

    @Test
    @DisplayName("Primeros 3 intentos dentro de la ventana deben permitirse")
    void primerosTresIntentosPasan() {
        UUID orden1 = UUID.randomUUID();
        UUID orden2 = UUID.randomUUID();
        UUID orden3 = UUID.randomUUID();

        ResultadoEvaluacionFraude r1 = detector.evaluarTransaccion(TARJETA_HASH, orden1);
        assertTrue(r1.permitido(), "El 1.º intento debe permitirse");
        assertEquals(1, r1.intentosEnVentana());

        ResultadoEvaluacionFraude r2 = detector.evaluarTransaccion(TARJETA_HASH, orden2);
        assertTrue(r2.permitido(), "El 2.º intento debe permitirse");
        assertEquals(2, r2.intentosEnVentana());

        ResultadoEvaluacionFraude r3 = detector.evaluarTransaccion(TARJETA_HASH, orden3);
        assertTrue(r3.permitido(), "El 3.º intento debe permitirse");
        assertEquals(3, r3.intentosEnVentana());
    }

    @Test
    @DisplayName("El 4.º intento dentro de la ventana debe bloquearse por fraude")
    void cuartoIntentoSeBloquea() {
        detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());

        // 4.º intento inmediato
        ResultadoEvaluacionFraude r4 = detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertFalse(r4.permitido(), "El 4.º intento debe ser bloqueado preventivamente");
        assertEquals(4, r4.intentosEnVentana());
        assertTrue(r4.motivo().contains("Bloqueado preventivamente por antifraude"));

        // 5.º intento también bloqueado
        ResultadoEvaluacionFraude r5 = detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertFalse(r5.permitido(), "El 5.º intento también debe bloquearse");
        assertEquals(5, r5.intentosEnVentana());
    }

    @Test
    @DisplayName("Pasada la ventana de tiempo, los intentos previos expiran y vuelve a permitir")
    void despuesDeExpirarVentanaVuelveAPermitir() throws InterruptedException {
        // Detector con ventana de 1 segundo
        InMemorySlidingWindowFraudeDetector quickDetector = new InMemorySlidingWindowFraudeDetector(3, 1);

        quickDetector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        quickDetector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        quickDetector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());

        // 4.º intento bloqueado
        ResultadoEvaluacionFraude r4 = quickDetector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertFalse(r4.permitido(), "4.º intento debe ser bloqueado");

        // Esperar 1.2 segundos para que expire la ventana
        Thread.sleep(1200);

        // Nuevo intento después de la ventana
        ResultadoEvaluacionFraude nuevoIntento = quickDetector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertTrue(nuevoIntento.permitido(), "Después de transcurrida la ventana, el intento debe ser permitido nuevamente");
        assertEquals(1, nuevoIntento.intentosEnVentana());
    }

    @Test
    @DisplayName("Distintas tarjetas tienen ventanas independientes")
    void tarjetasDistintasSonIndependientes() {
        String tarjetaA = "hash-tarjeta-A";
        String tarjetaB = "hash-tarjeta-B";

        detector.evaluarTransaccion(tarjetaA, UUID.randomUUID());
        detector.evaluarTransaccion(tarjetaA, UUID.randomUUID());
        detector.evaluarTransaccion(tarjetaA, UUID.randomUUID());

        // 4.º intento de tarjeta A se bloquea
        assertFalse(detector.evaluarTransaccion(tarjetaA, UUID.randomUUID()).permitido());

        // 1.º intento de tarjeta B debe permitirse sin verse afectado por A
        ResultadoEvaluacionFraude rB = detector.evaluarTransaccion(tarjetaB, UUID.randomUUID());
        assertTrue(rB.permitido());
        assertEquals(1, rB.intentosEnVentana());
    }

    @Test
    @DisplayName("Comportamiento correcto y thread-safe ante alta concurrencia")
    void evaluacionConcurrenteEsThreadSafe() throws InterruptedException {
        int numHilos = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numHilos);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger permitidos = new AtomicInteger(0);
        AtomicInteger bloqueados = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < numHilos; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    ResultadoEvaluacionFraude res = detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
                    if (res.permitido()) {
                        permitidos.incrementAndGet();
                    } else {
                        bloqueados.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        latch.countDown(); // Disparar todos los hilos simultáneamente
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(3, permitidos.get(), "Exactamente 3 intentos deben haber sido permitidos");
        assertEquals(numHilos - 3, bloqueados.get(), "Los restantes 17 intentos deben haber sido bloqueados");
    }
}
