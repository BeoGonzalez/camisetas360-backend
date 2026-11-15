package com.camisetas360.pago.adapter;

import com.camisetas360.pago.port.ConfirmarPagoResult;
import com.camisetas360.pago.port.IniciarPagoComando;
import com.camisetas360.pago.port.IniciarPagoResult;
import com.camisetas360.pago.port.PasarelaPagoPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Adaptador que simula el flujo de Webpay Plus de Transbank.
 *
 * Implementa PasarelaPagoPort para permitir que en producción se pueda sustituir
 * por un adaptador real (ej. Transbank SDK WebpayPlus.Transaction)
 * sin modificar la capa de dominio o servicios de la aplicación.
 *
 * Reglas deterministas de prueba para confirmarTransaccion:
 * - Tarjetas de prueba oficiales de Transbank (identificadas por sus ultimos 4 digitos,
 *   ya que el PAN nunca se almacena): resultado documentado por Transbank.
 * - Resto de tarjetas, por ultimo digito:
 * - Tarjeta terminada en '0': RECHAZADA (Fondos insuficientes)
 * - Tarjeta terminada en '5': RECHAZADA (Tarjeta expirada / rechazo emisor)
 * - Cualquier otra terminación (1, 2, 3, 4, 6, 7, 8, 9): AUTORIZADA
 */
@Component
public class WebpaySimuladoAdapter implements PasarelaPagoPort {

    private static final Logger log = LoggerFactory.getLogger(WebpaySimuladoAdapter.class);

    /**
     * Tarjetas de prueba de Webpay Plus (ambiente de integracion Transbank): ultimos 4 digitos -> autorizada.
     * VISA 4051 8856 0044 6623, AMEX 3700 0000 0002 032, MASTERCARD 5186 0595 5959 0568,
     * Redcompra 4051 8842 3993 7763 / 4511 3466 6003 7060 / 5186 0085 4123 3829,
     * Prepago VISA 4051 8860 0005 6590, Prepago MASTERCARD 5186 1741 1062 9480.
     */
    private static final Map<String, Boolean> TARJETAS_PRUEBA_TRANSBANK = Map.of(
            "6623", true,
            "2032", true,
            "0568", false,
            "7763", true,
            "7060", true,
            "3829", false,
            "6590", true,
            "9480", false
    );

    private final String baseUrl;

    public WebpaySimuladoAdapter(@Value("${webpay.simulado.base-url:http://localhost:8087}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public IniciarPagoResult iniciarTransaccion(IniciarPagoComando comando) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String urlRedireccion = baseUrl + "/api/v1/pago/retorno?token_ws=" + token;

        log.info("Webpay Simulado: Iniciando pago para orden {} por monto {}. Token generado: {}",
                comando.ordenId(), comando.monto(), token);

        return new IniciarPagoResult(token, urlRedireccion);
    }

    @Override
    public ConfirmarPagoResult confirmarTransaccion(String token, String tarjetaUltimos4) {
        log.info("Webpay Simulado: Confirmando transacción con token {} para tarjeta terminada en {}",
                token, tarjetaUltimos4);

        if (tarjetaUltimos4 == null || tarjetaUltimos4.isEmpty()) {
            return new ConfirmarPagoResult(false, null, "-3", "Datos de tarjeta incompletos");
        }

        Boolean resultadoTransbank = TARJETAS_PRUEBA_TRANSBANK.get(tarjetaUltimos4);
        if (Boolean.TRUE.equals(resultadoTransbank)) {
            return autorizar(token);
        }
        if (Boolean.FALSE.equals(resultadoTransbank)) {
            log.info("Webpay Simulado: Transacción rechazada (tarjeta de prueba Transbank de rechazo)");
            return new ConfirmarPagoResult(false, null, "-1", "Rechazada por el emisor (tarjeta de prueba Transbank)");
        }

        char ultimoDigito = tarjetaUltimos4.charAt(tarjetaUltimos4.length() - 1);

        if (ultimoDigito == '0') {
            log.info("Webpay Simulado: Transacción rechazada determinísticamente (termina en 0 - fondos insuficientes)");
            return new ConfirmarPagoResult(false, null, "-1", "Rechazada: Fondos insuficientes (simulación Webpay)");
        }

        if (ultimoDigito == '5') {
            log.info("Webpay Simulado: Transacción rechazada determinísticamente (termina en 5 - tarjeta expirada)");
            return new ConfirmarPagoResult(false, null, "-2", "Rechazada: Tarjeta expirada o rechazo emisor (simulación Webpay)");
        }

        return autorizar(token);
    }

    private ConfirmarPagoResult autorizar(String token) {
        String codigoAutorizacion = "AUTH-" + token.substring(0, Math.min(8, token.length())).toUpperCase();
        log.info("Webpay Simulado: Transacción autorizada determinísticamente con código {}", codigoAutorizacion);
        return new ConfirmarPagoResult(true, codigoAutorizacion, "0", "Transacción autorizada exitosamente");
    }
}
