package com.camisetas360.pago.webpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.camisetas360.pago.adapter.InMemorySlidingWindowFraudeDetector;
import com.camisetas360.pago.exception.FraudeDetectadoException;
import com.camisetas360.pago.exception.PagoInvalidoException;
import com.camisetas360.pago.messaging.PagoEvento;
import com.camisetas360.pago.messaging.PagoTopicos;
import com.camisetas360.pago.model.EstadoPago;
import com.camisetas360.pago.model.Pago;
import com.camisetas360.pago.repository.PagoRepository;
import com.camisetas360.pago.service.OutboxService;
import com.camisetas360.pago.webpay.WebpayPlusPort.Resultado;
import com.camisetas360.pago.webpay.WebpayPlusPort.Transaccion;
import com.camisetas360.pago.webpay.WebpayPlusPort.WebpayException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebpayPagoServiceTest {

    private static final String RETURN_URL = "https://front.example/api/v1/pago/webpay/retorno";
    private static final String USUARIO = "tenant:usuario-1";
    private static final String OTRO_USUARIO = "tenant:usuario-2";

    @Mock
    private PagoRepository pagos;
    @Mock
    private OutboxService outbox;
    @Mock
    private WebpayPlusPort webpay;

    private WebpayPagoService service;
    private final UUID ordenId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var properties = new WebpayProperties("https://webpay3gint.transbank.cl", "597055555532", "k",
                RETURN_URL, "https://front.example/pago/resultado", Duration.ofSeconds(5));
        // Regla real de Webpay: 1 intento por ventana de 10 s; la segunda llamada bloquea 30 min.
        service = new WebpayPagoService(pagos, outbox, webpay, properties,
                new InMemorySlidingWindowFraudeDetector(1, 10, 30), "secreto");
        lenient().when(pagos.save(any(Pago.class))).thenAnswer(inv -> {
            Pago p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });
    }

    @Test
    void iniciarCreaLaTransaccionYGuardaElPagoSinDatosDeTarjeta() {
        when(webpay.crear(anyString(), eq(ordenId.toString()), eq(15990L), eq(RETURN_URL)))
                .thenReturn(new Transaccion("tok-1", "https://webpay/init"));

        var inicio = service.iniciar(USUARIO, ordenId, new BigDecimal("15989.50"));

        assertThat(inicio.token()).isEqualTo("tok-1");
        assertThat(inicio.url()).isEqualTo("https://webpay/init");
        assertThat(inicio.monto()).isEqualByComparingTo("15990");
        var captor = ArgumentCaptor.forClass(Pago.class);
        verify(pagos).save(captor.capture());
        Pago pago = captor.getValue();
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.INICIADO);
        assertThat(pago.getTokenTransaccion()).isEqualTo("tok-1");
        assertThat(pago.getOrdenCompra()).startsWith("C360").hasSize(26);
        assertThat(pago.getTarjetaHash()).matches("[a-f0-9]{64}");
        assertThat(pago.getTarjetaUltimos4()).isNull();
        assertThat(pago.getTarjetaMarca()).isEqualTo("WEBPAY");
        var evento = ArgumentCaptor.forClass(PagoEvento.class);
        verify(outbox).registrarEvento(eq(PagoTopicos.INTENTO_REGISTRADO), eq(ordenId.toString()), evento.capture());
        assertThat(evento.getValue().ultimos4()).isEmpty();
    }

    @Test
    void noSePuedePagarDosVecesLaMismaOrden() {
        when(pagos.existsByOrdenIdAndEstado(ordenId, EstadoPago.AUTORIZADO)).thenReturn(true);

        assertThatThrownBy(() -> service.iniciar(USUARIO, ordenId, BigDecimal.TEN)).isInstanceOf(PagoInvalidoException.class);
        verifyNoInteractions(webpay);
    }

    @Test
    void montoQueRedondeaACeroSeRechaza() {
        assertThatThrownBy(() -> service.iniciar(USUARIO, ordenId, new BigDecimal("0.4")))
                .isInstanceOf(PagoInvalidoException.class);
        verifyNoInteractions(webpay);
    }

    @Test
    void segundaLlamadaEnDiezSegundosBloqueaAlUsuarioSinTocarWebpay() {
        when(webpay.crear(anyString(), anyString(), anyLong(), eq(RETURN_URL)))
                .thenReturn(new Transaccion("tok-1", "https://webpay/init"));
        service.iniciar(USUARIO, ordenId, BigDecimal.TEN);

        var segunda = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.iniciar(USUARIO, ordenId, BigDecimal.TEN), FraudeDetectadoException.class);

        assertThat(segunda).isNotNull();
        assertThat(segunda.getSegundosParaReintentar()).isPositive().isLessThanOrEqualTo(30 * 60);
        verify(webpay, times(1)).crear(anyString(), anyString(), anyLong(), eq(RETURN_URL));
        verify(pagos, times(1)).save(any(Pago.class));
    }

    @Test
    void elBloqueoEsPorUsuario() {
        when(webpay.crear(anyString(), anyString(), anyLong(), eq(RETURN_URL)))
                .thenReturn(new Transaccion("tok-1", "https://webpay/init"));
        service.iniciar(USUARIO, ordenId, BigDecimal.TEN);

        var otroUsuario = service.iniciar(OTRO_USUARIO, ordenId, BigDecimal.TEN);

        assertThat(otroUsuario.token()).isEqualTo("tok-1");
    }

    @Test
    void retornoAprobadoConfirmaYPublicaAutorizado() {
        Pago pago = pagoIniciado();
        when(webpay.confirmar("tok-1")).thenReturn(
                new Resultado(true, "AUTHORIZED", 0, "1213", "XXXXXXXXXXXX6623", "C360X", 15990L, "VN"));

        var r = service.procesarRetorno("tok-1", null, null);

        assertThat(r.aprobado()).isTrue();
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.AUTORIZADO);
        assertThat(pago.getCodigoAutorizacion()).isEqualTo("1213");
        assertThat(pago.getTarjetaUltimos4()).isEqualTo("6623");
        assertThat(pago.getTarjetaMarca()).isEqualTo("WEBPAY-VN");
        verify(outbox).registrarEvento(eq(PagoTopicos.AUTORIZADO), anyString(), any());
    }

    @Test
    void retornoRechazadoPublicaRechazado() {
        Pago pago = pagoIniciado();
        when(webpay.confirmar("tok-1")).thenReturn(
                new Resultado(false, "FAILED", -1, null, "0568", "C360X", 15990L, null));

        var r = service.procesarRetorno("tok-1", null, null);

        assertThat(r.aprobado()).isFalse();
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.RECHAZADO);
        assertThat(pago.getMensajeRespuesta()).contains("FAILED", "-1");
        assertThat(pago.getTarjetaMarca()).isEqualTo("WEBPAY");
        verify(outbox).registrarEvento(eq(PagoTopicos.RECHAZADO), anyString(), any());
    }

    @Test
    void siElCommitFallaDecideElEstadoDeTransbank() {
        Pago pago = pagoIniciado();
        when(webpay.confirmar("tok-1")).thenThrow(new WebpayException("422", 422, null));
        when(webpay.estado("tok-1")).thenReturn(new Resultado(true, "AUTHORIZED", 0, "1213", "6623", "C", 1L, "VD"));

        assertThat(service.procesarRetorno("tok-1", null, null).aprobado()).isTrue();
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.AUTORIZADO);
    }

    @Test
    void siTransbankNoRespondeElPagoQuedaPendiente() {
        Pago pago = pagoIniciado();
        when(webpay.confirmar("tok-1")).thenThrow(new WebpayException("red", 0, null));
        when(webpay.estado("tok-1")).thenThrow(new WebpayException("red", 0, null));

        var r = service.procesarRetorno("tok-1", null, null);

        assertThat(r.aprobado()).isFalse();
        assertThat(r.pago()).contains(pago);
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.INICIADO);
        verifyNoInteractions(outbox);
    }

    @Test
    void transaccionAunInicializadaNoSeMarcaRechazada() {
        Pago pago = pagoIniciado();
        when(webpay.confirmar("tok-1")).thenReturn(new Resultado(false, "INITIALIZED", null, null, null, null, null, null));

        assertThat(service.procesarRetorno("tok-1", null, null).aprobado()).isFalse();
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.INICIADO);
        verifyNoInteractions(outbox);
    }

    @Test
    void recargarElRetornoNoVuelveAConfirmar() {
        Pago pago = pagoIniciado();
        pago.setEstado(EstadoPago.AUTORIZADO);

        assertThat(service.procesarRetorno("tok-1", null, null).aprobado()).isTrue();
        verifyNoInteractions(webpay, outbox);
    }

    @Test
    void tokenDesconocido() {
        when(pagos.findByTokenTransaccion("otro")).thenReturn(Optional.empty());

        var r = service.procesarRetorno("otro", null, null);

        assertThat(r.aprobado()).isFalse();
        assertThat(r.pago()).isEmpty();
    }

    @Test
    void anuladoPorElClienteSeRechazaSinCommit() {
        Pago pago = pagoIniciado();

        var r = service.procesarRetorno(null, "tok-1", "C360X");

        assertThat(r.aprobado()).isFalse();
        assertThat(pago.getEstado()).isEqualTo(EstadoPago.RECHAZADO);
        assertThat(pago.getMensajeRespuesta()).contains("anulado");
        verify(webpay, never()).confirmar(anyString());
        verify(outbox).registrarEvento(eq(PagoTopicos.RECHAZADO), anyString(), any());
    }

    @Test
    void errorEnElFormularioTraeAmbosTokensYNoSeConfirma() {
        pagoIniciado();

        assertThat(service.procesarRetorno("tok-1", "tok-1", "C360X").aprobado()).isFalse();
        verify(webpay, never()).confirmar(anyString());
    }

    @Test
    void tiempoAgotadoSeBuscaPorOrdenDeCompra() {
        Pago pago = pagoIniciado();
        when(pagos.findByOrdenCompra("C360X")).thenReturn(Optional.of(pago));

        assertThat(service.procesarRetorno(null, null, "C360X").aprobado()).isFalse();
        assertThat(pago.getMensajeRespuesta()).contains("Tiempo");
    }

    @Test
    void anulacionDeUnPagoYaAutorizadoNoLoCambia() {
        Pago pago = pagoIniciado();
        pago.setEstado(EstadoPago.AUTORIZADO);

        assertThat(service.procesarRetorno(null, "tok-1", null).aprobado()).isTrue();
        verifyNoInteractions(outbox);
    }

    @Test
    void anulacionYTiempoAgotadoDesconocidos() {
        when(pagos.findByTokenTransaccion("x")).thenReturn(Optional.empty());
        when(pagos.findByOrdenCompra("y")).thenReturn(Optional.empty());

        assertThat(service.procesarRetorno(null, "x", null).pago()).isEmpty();
        assertThat(service.procesarRetorno(null, null, "y").pago()).isEmpty();
        assertThat(service.procesarRetorno(null, " ", "").pago()).isEmpty();
        verify(webpay, never()).crear(anyString(), anyString(), anyLong(), anyString());
    }

    private Pago pagoIniciado() {
        Pago pago = new Pago();
        pago.setId(UUID.randomUUID());
        pago.setOrdenId(ordenId);
        pago.setMonto(new BigDecimal("15990"));
        pago.setEstado(EstadoPago.INICIADO);
        pago.setTokenTransaccion("tok-1");
        pago.setOrdenCompra("C360X");
        pago.setTarjetaHash("a".repeat(64));
        pago.setTarjetaMarca("WEBPAY");
        lenient().when(pagos.findByTokenTransaccion("tok-1")).thenReturn(Optional.of(pago));
        return pago;
    }
}
