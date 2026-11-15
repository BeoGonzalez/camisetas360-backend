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

    /** Igual que la configuracion por defecto: 2 intentos permitidos, el 3.º bloquea. */
    private static final int MAX_INTENTOS = 2;

    @BeforeEach
    void setUp() {
        // Umbral: 2 intentos, ventana: 2 segundos, bloqueo: 30 minutos
        detector = new InMemorySlidingWindowFraudeDetector(MAX_INTENTOS, 2, 30);
    }

    @Test
    @DisplayName("Los primeros 2 intentos dentro de la ventana se permiten")
    void primerosDosIntentosPasan() {
        ResultadoEvaluacionFraude r1 = detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertTrue(r1.permitido(), "El 1.º intento debe permitirse");
        assertEquals(1, r1.intentosEnVentana());

        ResultadoEvaluacionFraude r2 = detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertTrue(r2.permitido(), "El 2.º intento debe permitirse");
        assertEquals(2, r2.intentosEnVentana());
    }

    @Test
    @DisplayName("El 3.º intento dentro de la ventana bloquea la tarjeta 30 minutos")
    void tercerIntentoSeBloquea() {
        detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());

        // 3.º intento inmediato
        ResultadoEvaluacionFraude r3 = detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertFalse(r3.permitido(), "El 3.º intento debe ser bloqueado preventivamente");
        assertEquals(3, r3.intentosEnVentana());
        assertTrue(r3.motivo().contains("Bloqueado preventivamente por antifraude"));
        assertTrue(r3.motivo().contains("máximo permitido: 2"), r3.motivo());
        assertTrue(r3.motivo().contains("30 minutos"), r3.motivo());
        assertTrue(r3.segundosParaReintentar() > 29 * 60 && r3.segundosParaReintentar() <= 30 * 60,
                "Retry-After cercano a 30 minutos: " + r3.segundosParaReintentar());

        // 4.º intento también bloqueado, sin contarse como intento nuevo
        ResultadoEvaluacionFraude r4 = detector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertFalse(r4.permitido(), "El 4.º intento también debe bloquearse");
        assertEquals(3, r4.intentosEnVentana());
        assertTrue(r4.motivo().startsWith("Tarjeta bloqueada por antifraude hasta"), r4.motivo());
    }

    @Test
    @DisplayName("Sin bloqueo, al expirar la ventana los intentos previos dejan de contar")
    void despuesDeExpirarVentanaVuelveAPermitir() {
        // Reloj controlado: el test no depende de sleeps ni de la velocidad del runner de CI.
        RelojManual reloj = new RelojManual();
        InMemorySlidingWindowFraudeDetector quickDetector =
                new InMemorySlidingWindowFraudeDetector(MAX_INTENTOS, 1, 30, reloj);

        quickDetector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        quickDetector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());

        // Al cumplirse la ventana, los 2 intentos expiran: el 3.º ya no supera el maximo
        reloj.avanzar(1000);
        ResultadoEvaluacionFraude nuevoIntento = quickDetector.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertTrue(nuevoIntento.permitido(), "Fuera de la ventana el intento debe permitirse");
        assertEquals(1, nuevoIntento.intentosEnVentana());
        assertEquals(0, nuevoIntento.segundosParaReintentar());
    }

    @Test
    @DisplayName("El bloqueo dura exactamente 30 minutos y los reintentos no lo alargan")
    void bloqueoDuraTreintaMinutosSinAlargarse() {
        RelojManual reloj = new RelojManual();
        InMemorySlidingWindowFraudeDetector conReloj = new InMemorySlidingWindowFraudeDetector(MAX_INTENTOS, 10, 30, reloj);
        for (int i = 0; i < MAX_INTENTOS; i++) {
            assertTrue(conReloj.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID()).permitido());
        }
        ResultadoEvaluacionFraude bloqueo = conReloj.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertFalse(bloqueo.permitido());
        assertEquals(1800, bloqueo.segundosParaReintentar());

        // Reintentos durante el bloqueo: siguen rechazados y la cuenta regresiva no se reinicia
        reloj.avanzar(10 * 60_000L);
        ResultadoEvaluacionFraude aLos10 = conReloj.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertFalse(aLos10.permitido());
        assertEquals(1200, aLos10.segundosParaReintentar());

        reloj.avanzar(20 * 60_000L - 1);
        ResultadoEvaluacionFraude ultimoMilisegundo = conReloj.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertFalse(ultimoMilisegundo.permitido(), "Un milisegundo antes de los 30 minutos sigue bloqueada");
        assertEquals(1, ultimoMilisegundo.segundosParaReintentar());

        // A los 30 minutos exactos se desbloquea y empieza de cero
        reloj.avanzar(1);
        ResultadoEvaluacionFraude desbloqueada = conReloj.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        assertTrue(desbloqueada.permitido(), "A los 30 minutos la tarjeta se desbloquea");
        assertEquals(1, desbloqueada.intentosEnVentana(), "El conteo empieza de cero tras el bloqueo");
    }

    @Test
    @DisplayName("La duracion del bloqueo es configurable (antifraude.bloqueo-minutos)")
    void duracionDelBloqueoConfigurable() {
        RelojManual reloj = new RelojManual();
        InMemorySlidingWindowFraudeDetector cincoMinutos = new InMemorySlidingWindowFraudeDetector(MAX_INTENTOS, 10, 5, reloj);
        for (int i = 0; i < MAX_INTENTOS; i++) {
            cincoMinutos.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        }
        assertEquals(300, cincoMinutos.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID()).segundosParaReintentar());
        assertThrows(IllegalArgumentException.class,
                () -> new InMemorySlidingWindowFraudeDetector(MAX_INTENTOS, 10, 0, reloj));
    }

    @Test
    @DisplayName("La limpieza periodica elimina tarjetas sin intentos vigentes y conserva las activas")
    void limpiezaEliminaSoloVentanasExpiradas() {
        RelojManual reloj = new RelojManual();
        InMemorySlidingWindowFraudeDetector conReloj = new InMemorySlidingWindowFraudeDetector(MAX_INTENTOS, 10, 30, reloj);
        conReloj.evaluarTransaccion("tarjeta-inactiva", UUID.randomUUID());
        reloj.avanzar(9_000);
        conReloj.evaluarTransaccion("tarjeta-activa", UUID.randomUUID());
        reloj.avanzar(1_000);

        conReloj.limpiarEntradasExpiradas();

        assertEquals(1, conReloj.evaluarTransaccion("tarjeta-inactiva", UUID.randomUUID()).intentosEnVentana(),
                "la tarjeta inactiva empieza de cero");
        assertEquals(2, conReloj.evaluarTransaccion("tarjeta-activa", UUID.randomUUID()).intentosEnVentana(),
                "la tarjeta activa conserva su intento vigente");
    }

    @Test
    @DisplayName("La limpieza periodica nunca levanta un bloqueo vigente")
    void limpiezaConservaBloqueosVigentes() {
        RelojManual reloj = new RelojManual();
        InMemorySlidingWindowFraudeDetector conReloj = new InMemorySlidingWindowFraudeDetector(MAX_INTENTOS, 10, 30, reloj);
        for (int i = 0; i <= MAX_INTENTOS; i++) {
            conReloj.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID());
        }
        reloj.avanzar(15 * 60_000L); // la ventana de 10 s ya expiro, el bloqueo no
        conReloj.limpiarEntradasExpiradas();

        assertFalse(conReloj.evaluarTransaccion(TARJETA_HASH, UUID.randomUUID()).permitido(),
                "Tras la limpieza la tarjeta sigue bloqueada");
    }

    /** Reloj que solo avanza cuando el test lo pide. */
    private static final class RelojManual extends java.time.Clock {
        private long millis = 1_700_000_000_000L;

        void avanzar(long ms) {
            millis += ms;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    @DisplayName("Distintas tarjetas tienen ventanas independientes")
    void tarjetasDistintasSonIndependientes() {
        String tarjetaA = "hash-tarjeta-A";
        String tarjetaB = "hash-tarjeta-B";

        detector.evaluarTransaccion(tarjetaA, UUID.randomUUID());
        detector.evaluarTransaccion(tarjetaA, UUID.randomUUID());

        // 3.º intento de tarjeta A se bloquea
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

        assertEquals(MAX_INTENTOS, permitidos.get(), "Exactamente 2 intentos deben haber sido permitidos");
        assertEquals(numHilos - MAX_INTENTOS, bloqueados.get(), "Los restantes 18 intentos deben haber sido bloqueados");
    }
}
