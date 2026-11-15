package com.camisetas360.analytics.service;

import com.camisetas360.analytics.model.AlertaFraude;
import com.camisetas360.analytics.model.InteraccionEvento;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static com.camisetas360.analytics.support.Eventos.envio;
import static com.camisetas360.analytics.support.Eventos.evento;
import static org.assertj.core.api.Assertions.assertThat;

class MetricasServiceTest {

    /** Reloj controlable para simular el paso del tiempo. */
    static final class RelojMovible extends Clock {
        private Instant ahora;

        RelojMovible(Instant inicio) {
            this.ahora = inicio;
        }

        void avanzar(Duration duracion) {
            ahora = ahora.plus(duracion);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    private final RelojMovible reloj = new RelojMovible(Instant.parse("2026-11-10T12:00:30Z"));
    private final MetricasService metricas = new MetricasService(reloj, 20, 60);

    private void vista(String usuario, String sku, String nombre) {
        metricas.registrar(evento(InteraccionEvento.PRODUCTO_VISTO, usuario, sku, nombre, "Liga", 1.0, null, reloj.instant()));
    }

    @Test
    void snapshot_calculaClicsUsuariosTopYTipos() {
        vista("u1", "A", "Camiseta A");
        vista("u1", "A", "Camiseta A");
        vista("u2", "B", "Camiseta B");
        metricas.registrar(evento(InteraccionEvento.ITEM_AGREGADO, "u2", "B", "Camiseta B", "Liga", 1.0, 1, reloj.instant()));
        metricas.registrar(envio("u3", reloj.instant()));

        var snapshot = metricas.snapshot();

        assertThat(snapshot.clicsUltimoMinuto()).isEqualTo(5);
        assertThat(snapshot.usuariosActivos()).isEqualTo(3);
        assertThat(snapshot.productosMasVistos()).containsExactly(
                new MetricasSnapshot.ProductoVisto("A", "Camiseta A", 2),
                new MetricasSnapshot.ProductoVisto("B", "Camiseta B", 1));
        assertThat(snapshot.eventosPorTipo()).containsEntry("PRODUCTO_VISTO", 3L)
                .containsEntry("ITEM_AGREGADO", 1L).containsEntry("ENVIO_ACTUALIZADO", 1L);
        assertThat(snapshot.serieClics()).hasSize(15);
        assertThat(snapshot.serieClics().getLast()).isEqualTo(new MetricasSnapshot.PuntoSerie("2026-11-10T12:00:00Z", 5));
        assertThat(snapshot.alertas()).isEmpty();
    }

    @Test
    void snapshot_descartaLoAnteriorALaVentana() {
        vista("u1", "A", "Camiseta A");
        reloj.avanzar(Duration.ofMinutes(2));
        vista("u2", "B", "Camiseta B");

        var snapshot = metricas.snapshot();
        assertThat(snapshot.clicsUltimoMinuto()).isEqualTo(1);
        assertThat(snapshot.eventosPorTipo()).containsEntry("PRODUCTO_VISTO", 2L);

        reloj.avanzar(Duration.ofMinutes(10));
        assertThat(metricas.snapshot().usuariosActivos()).isZero();

        reloj.avanzar(Duration.ofMinutes(60));
        var vacio = metricas.snapshot();
        assertThat(vacio.eventosPorTipo()).isEmpty();
        assertThat(vacio.productosMasVistos()).isEmpty();
        assertThat(vacio.serieClics()).extracting(MetricasSnapshot.PuntoSerie::clics).containsOnly(0L);
    }

    @Test
    void alerta_navegacionAnomala_unaVezPorMinutoYSinIdCompleto() {
        for (int i = 0; i < 25; i++) {
            vista("tenant:usuario-sospechoso-9f3a", "A", "Camiseta A");
        }

        var alertas = metricas.snapshot().alertas();
        assertThat(alertas).singleElement().satisfies(alerta -> {
            assertThat(alerta.tipo()).isEqualTo("NAVEGACION_ANOMALA");
            assertThat(alerta.mensaje()).contains("...9f3a").doesNotContain("usuario-sospechoso");
        });
    }

    @Test
    void alerta_picoDeTrafico() {
        for (int i = 0; i < 61; i++) {
            vista("u" + i, "A", "Camiseta A");
        }

        assertThat(metricas.snapshot().alertas()).extracting(MetricasSnapshot.Alerta::tipo).containsExactly("PICO_TRAFICO");
    }

    @Test
    void alerta_fraude_muestraSoloUltimos4() {
        metricas.registrarFraude(new AlertaFraude("orden-1", 39990.0, "9012", "4 intentos en 10 segundos"));

        assertThat(metricas.snapshot().alertas()).singleElement().satisfies(alerta -> {
            assertThat(alerta.tipo()).isEqualTo("FRAUDE");
            assertThat(alerta.mensaje()).isEqualTo("Pago bloqueado por antifraude (tarjeta ****9012, orden orden-1)");
        });
    }

    @Test
    void alertas_conservaSoloLas20MasRecientes() {
        for (int i = 0; i < 25; i++) {
            metricas.registrarFraude(new AlertaFraude("orden-" + i, 1.0, "0000", "x"));
        }

        var alertas = metricas.snapshot().alertas();
        assertThat(alertas).hasSize(20);
        assertThat(alertas.getFirst().mensaje()).contains("orden-24");
    }
}
