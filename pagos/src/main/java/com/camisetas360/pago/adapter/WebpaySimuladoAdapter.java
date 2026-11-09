package com.camisetas360.pago.adapter;

import com.camisetas360.pago.port.ConfirmarPagoResult;
import com.camisetas360.pago.port.IniciarPagoComando;
import com.camisetas360.pago.port.IniciarPagoResult;
import com.camisetas360.pago.port.PasarelaPagoPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adaptador que simula el flujo de Webpay Plus de Transbank.
 *
 * Implementa PasarelaPagoPort para permitir que en producción se pueda sustituir
 * por un adaptador real (ej. Transbank SDK WebpayPlus.Transaction)
 * sin modificar la capa de dominio o servicios de la aplicación.
 *
 * Reglas deterministas de prueba para confirmarTransaccion:
 * - Tarjeta terminada en '0': RECHAZADA (Fondos insuficientes)
 * - Tarjeta terminada en '5': RECHAZADA (Tarjeta expirada / rechazo emisor)
 * - Cualquier otra terminación (1, 2, 3, 4, 6, 7, 8, 9): AUTORIZADA
 */
@Component
public class WebpaySimuladoAdapter implements PasarelaPagoPort {

    private static final Logger log = LoggerFactory.getLogger(WebpaySimuladoAdapter.class);

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

        char ultimoDigito = tarjetaUltimos4.charAt(tarjetaUltimos4.length() - 1);

        if (ultimoDigito == '0') {
            log.info("Webpay Simulado: Transacción rechazada determinísticamente (termina en 0 - fondos insuficientes)");
            return new ConfirmarPagoResult(false, null, "-1", "Rechazada: Fondos insuficientes (simulación Webpay)");
        }

        if (ultimoDigito == '5') {
            log.info("Webpay Simulado: Transacción rechazada determinísticamente (termina en 5 - tarjeta expirada)");
            return new ConfirmarPagoResult(false, null, "-2", "Rechazada: Tarjeta expirada o rechazo emisor (simulación Webpay)");
        }

        String codigoAutorizacion = "AUTH-" + token.substring(0, Math.min(8, token.length())).toUpperCase();
        log.info("Webpay Simulado: Transacción autorizada determinísticamente con código {}", codigoAutorizacion);
        return new ConfirmarPagoResult(true, codigoAutorizacion, "0", "Transacción autorizada exitosamente");
    }
}
