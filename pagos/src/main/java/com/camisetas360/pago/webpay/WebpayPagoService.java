package com.camisetas360.pago.webpay;

import com.camisetas360.pago.exception.PagoInvalidoException;
import com.camisetas360.pago.messaging.PagoEvento;
import com.camisetas360.pago.messaging.PagoTopicos;
import com.camisetas360.pago.model.EstadoPago;
import com.camisetas360.pago.model.Pago;
import com.camisetas360.pago.repository.PagoRepository;
import com.camisetas360.pago.service.OutboxService;
import com.camisetas360.pago.util.TarjetaUtils;
import com.camisetas360.pago.webpay.WebpayPlusPort.Resultado;
import com.camisetas360.pago.webpay.WebpayPlusPort.WebpayException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pago con Webpay Plus real: Camisetas360 crea la transaccion, el cliente paga en la pagina de Transbank
 * y Webpay lo devuelve a {@code /api/v1/pago/webpay/retorno}, donde se confirma (commit) y se decide si
 * la compra fue aprobada o rechazada. Los datos de la tarjeta nunca pasan por Camisetas360.
 */
@Service
public class WebpayPagoService {

    static final String MARCA = "WEBPAY";

    private static final Logger log = LoggerFactory.getLogger(WebpayPagoService.class);

    private final PagoRepository pagos;
    private final OutboxService outbox;
    private final WebpayPlusPort webpay;
    private final WebpayProperties properties;
    private final String secretoHash;

    public WebpayPagoService(PagoRepository pagos, OutboxService outbox, WebpayPlusPort webpay,
                             WebpayProperties properties,
                             @Value("${antifraude.secreto-hash}") String secretoHash) {
        this.pagos = pagos;
        this.outbox = outbox;
        this.webpay = webpay;
        this.properties = properties;
        this.secretoHash = secretoHash;
    }

    public record Inicio(UUID pagoId, UUID ordenId, BigDecimal monto, String token, String url) {
    }

    /** Resultado del retorno de Webpay: el pago resuelto, o vacio si no se reconoce la transaccion. */
    public record Retorno(boolean aprobado, Optional<Pago> pago) {
    }

    @Transactional
    public Inicio iniciar(UUID ordenId, BigDecimal monto) {
        if (pagos.existsByOrdenIdAndEstado(ordenId, EstadoPago.AUTORIZADO)) {
            throw new PagoInvalidoException("La orden " + ordenId + " ya tiene un pago aprobado");
        }
        // Webpay cobra pesos chilenos enteros.
        BigDecimal pesos = monto.setScale(0, RoundingMode.HALF_UP);
        if (pesos.signum() <= 0) {
            throw new PagoInvalidoException("El monto debe ser mayor a 0");
        }
        // buy_order: maximo 26 caracteres, unico por transaccion.
        String ordenCompra = "C360" + UUID.randomUUID().toString().replace("-", "").substring(0, 22);
        var transaccion = webpay.crear(ordenCompra, ordenId.toString(), pesos.longValueExact(), properties.returnUrl());

        Pago pago = new Pago();
        pago.setOrdenId(ordenId);
        pago.setMonto(pesos);
        pago.setMoneda("CLP");
        pago.setEstado(EstadoPago.INICIADO);
        pago.setTokenTransaccion(transaccion.token());
        pago.setOrdenCompra(ordenCompra);
        // No hay PAN: el hash identifica de forma opaca la transaccion de Webpay (contrato pago-events).
        pago.setTarjetaHash(TarjetaUtils.calcularHmacSha256("webpay:" + ordenCompra, secretoHash));
        pago.setTarjetaMarca(MARCA);
        pago = pagos.save(pago);

        publicar(PagoTopicos.INTENTO_REGISTRADO, "PAGO_INTENTO_REGISTRADO", pago, "Pago iniciado en Webpay Plus");
        return new Inicio(pago.getId(), ordenId, pesos, transaccion.token(), transaccion.url());
    }

    /**
     * Procesa la vuelta desde Webpay segun los parametros que envia Transbank:
     * <ul>
     *   <li>{@code token_ws}: pago terminado -&gt; commit y aprobado/rechazado segun Transbank.</li>
     *   <li>{@code TBK_TOKEN} (con o sin token_ws): el cliente anulo o hubo error en el formulario -&gt; rechazado, sin commit.</li>
     *   <li>solo {@code TBK_ORDEN_COMPRA}: se agoto el tiempo para pagar -&gt; rechazado.</li>
     * </ul>
     */
    @Transactional
    public Retorno procesarRetorno(String tokenWs, String tbkToken, String tbkOrdenCompra) {
        if (tbkToken != null && !tbkToken.isBlank()) {
            return rechazarSiPendiente(pagos.findByTokenTransaccion(tbkToken), "Pago anulado en Webpay");
        }
        if (tokenWs != null && !tokenWs.isBlank()) {
            return confirmar(tokenWs);
        }
        if (tbkOrdenCompra != null && !tbkOrdenCompra.isBlank()) {
            return rechazarSiPendiente(pagos.findByOrdenCompra(tbkOrdenCompra), "Tiempo para pagar agotado en Webpay");
        }
        return new Retorno(false, Optional.empty());
    }

    private Retorno confirmar(String token) {
        Optional<Pago> encontrado = pagos.findByTokenTransaccion(token);
        if (encontrado.isEmpty()) {
            log.warn("Webpay Plus: retorno con un token desconocido");
            return new Retorno(false, Optional.empty());
        }
        Pago pago = encontrado.get();
        if (pago.getEstado() != EstadoPago.INICIADO) {
            // Recarga de la pagina de retorno: ya se resolvio.
            return new Retorno(pago.getEstado() == EstadoPago.AUTORIZADO, Optional.of(pago));
        }
        Resultado resultado;
        try {
            resultado = webpay.confirmar(token);
        } catch (WebpayException e) {
            // Si el commit fallo (p. ej. ya se habia confirmado o hubo un corte de red), el estado de
            // Transbank decide; asi nunca se marca rechazado un pago que si se cobro.
            try {
                resultado = webpay.estado(token);
            } catch (WebpayException ignorada) {
                log.warn("Webpay Plus: no se pudo confirmar ni consultar el pago {}; queda INICIADO", pago.getId());
                return new Retorno(false, Optional.of(pago));
            }
        }
        if (resultado.aprobada()) {
            pago.setEstado(EstadoPago.AUTORIZADO);
            pago.setCodigoAutorizacion(resultado.codigoAutorizacion());
            pago.setMensajeRespuesta("Transaccion autorizada por Webpay");
        } else if ("INITIALIZED".equals(resultado.estado())) {
            // Transbank aun no la da por terminada: no se puede afirmar que fue rechazada.
            return new Retorno(false, Optional.of(pago));
        } else {
            pago.setEstado(EstadoPago.RECHAZADO);
            pago.setMensajeRespuesta("Rechazada por Webpay (status " + resultado.estado()
                    + ", response_code " + resultado.codigoRespuesta() + ")");
        }
        if (resultado.ultimos4() != null) {
            String digitos = resultado.ultimos4().replaceAll("\\D", "");
            pago.setTarjetaUltimos4(digitos.length() > 4 ? digitos.substring(digitos.length() - 4) : digitos);
        }
        if (resultado.tipoPago() != null) {
            pago.setTarjetaMarca(MARCA + "-" + resultado.tipoPago());
        }
        pago = pagos.save(pago);
        boolean aprobado = pago.getEstado() == EstadoPago.AUTORIZADO;
        publicar(aprobado ? PagoTopicos.AUTORIZADO : PagoTopicos.RECHAZADO,
                aprobado ? "PAGO_AUTORIZADO" : "PAGO_RECHAZADO", pago, pago.getMensajeRespuesta());
        return new Retorno(aprobado, Optional.of(pago));
    }

    private Retorno rechazarSiPendiente(Optional<Pago> encontrado, String motivo) {
        if (encontrado.isEmpty()) {
            return new Retorno(false, Optional.empty());
        }
        Pago pago = encontrado.get();
        if (pago.getEstado() == EstadoPago.INICIADO) {
            pago.setEstado(EstadoPago.RECHAZADO);
            pago.setMensajeRespuesta(motivo);
            pago = pagos.save(pago);
            publicar(PagoTopicos.RECHAZADO, "PAGO_RECHAZADO", pago, motivo);
        }
        return new Retorno(pago.getEstado() == EstadoPago.AUTORIZADO, Optional.of(pago));
    }

    private void publicar(String topico, String tipo, Pago pago, String motivo) {
        PagoEvento evento = PagoEvento.crear(tipo, pago.getOrdenId(), pago.getId(), pago.getMonto(),
                pago.getEstado().name(), pago.getTarjetaHash(),
                pago.getTarjetaUltimos4() == null ? "" : pago.getTarjetaUltimos4(), motivo);
        outbox.registrarEvento(topico, pago.getOrdenId().toString(), evento);
    }
}
