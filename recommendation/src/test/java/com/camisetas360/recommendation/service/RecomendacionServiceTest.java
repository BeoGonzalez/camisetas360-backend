package com.camisetas360.recommendation.service;

import com.camisetas360.recommendation.model.Accion;
import com.camisetas360.recommendation.model.Interaccion;
import com.camisetas360.recommendation.model.ProductoFicha;
import com.camisetas360.recommendation.repository.InteraccionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecomendacionServiceTest {

    private final InteraccionRepository repository = mock(InteraccionRepository.class);
    private final RecomendacionService service = new RecomendacionService(repository);
    private final Interaccion interaccion = interaccion("t:o", "CHI-LOC", Accion.VISTA);

    @Test
    void interaccionNuevaBloqueaUsuarioAntesDeEscribirYActualizaProductoAlFinal() {
        when(repository.guardarSiNoExiste(interaccion)).thenReturn(true);

        assertThat(service.registrar(interaccion)).isTrue();

        // El lock del usuario va primero y la fila del producto al final: mismo orden en todas las
        // transacciones, por eso Kafka y RabbitMQ no pueden bloquearse mutuamente.
        var orden = inOrder(repository);
        orden.verify(repository).bloquearUsuario("t:o");
        orden.verify(repository).guardarSiNoExiste(interaccion);
        orden.verify(repository).recalcularTop5("t:o");
        orden.verify(repository).actualizarProducto(interaccion.producto());
    }

    @Test
    void interaccionDuplicadaNoRecalcula() {
        when(repository.guardarSiNoExiste(interaccion)).thenReturn(false);

        assertThat(service.registrar(interaccion)).isFalse();

        verify(repository, never()).actualizarProducto(any());
        verify(repository, never()).recalcularTop5(anyString());
    }

    @Test
    void variasInteraccionesRecalculanUnaVezYActualizanProductosOrdenadosPorSku() {
        var b = interaccion("t:o", "B-SKU", Accion.COMPRA);
        var a = interaccion("t:o", "A-SKU", Accion.COMPRA);
        var duplicada = interaccion("t:o", "C-SKU", Accion.COMPRA);
        when(repository.guardarSiNoExiste(b)).thenReturn(true);
        when(repository.guardarSiNoExiste(a)).thenReturn(true);
        when(repository.guardarSiNoExiste(duplicada)).thenReturn(false);

        assertThat(service.registrarTodas(List.of(b, duplicada, a))).isEqualTo(2);

        var orden = inOrder(repository);
        orden.verify(repository).bloquearUsuario("t:o");
        orden.verify(repository, times(3)).guardarSiNoExiste(any());
        orden.verify(repository).recalcularTop5("t:o");
        orden.verify(repository).actualizarProducto(a.producto());
        orden.verify(repository).actualizarProducto(b.producto());
        verify(repository, times(1)).bloquearUsuario(anyString());
        verify(repository, never()).actualizarProducto(duplicada.producto());
    }

    @Test
    void interaccionesDeVariosUsuariosSeRechazan() {
        var otra = interaccion("t:otro", "A-SKU", Accion.VISTA);

        assertThatThrownBy(() -> service.registrarTodas(List.of(interaccion, otra)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void listaVaciaNoTocaLaBase() {
        assertThat(service.registrarTodas(List.of())).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void top5DelegaEnElRepositorio() {
        service.top5("t:o");
        verify(repository).top5("t:o");
    }

    private static Interaccion interaccion(String usuario, String sku, Accion accion) {
        return new Interaccion(UUID.randomUUID(), usuario, new ProductoFicha(sku, "Camiseta " + sku, "Selecciones", 29990.0),
                accion, "catalog", Instant.now());
    }
}
