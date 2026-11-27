package com.camisetas360.pago.webpay;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webpay Plus (Transbank, API REST v1.2).
 *
 * <p>Por defecto apunta al ambiente de INTEGRACION con el codigo de comercio y la API key de prueba
 * publicados por Transbank (no cobran). En produccion se reemplazan por los del comercio
 * ({@code WEBPAY_BASE_URL=https://webpay3g.transbank.cl}, {@code WEBPAY_CODIGO_COMERCIO}, {@code WEBPAY_API_KEY}).
 *
 * @param baseUrl         host de la API de Transbank
 * @param codigoComercio  Tbk-Api-Key-Id
 * @param apiKey          Tbk-Api-Key-Secret
 * @param returnUrl       URL publica de {@code /api/v1/pago/webpay/retorno}, a la que Webpay devuelve al cliente
 * @param resultadoUrl    pagina del frontend que muestra si la compra fue aprobada o rechazada
 * @param timeout         timeout de conexion y lectura hacia Transbank
 */
@ConfigurationProperties(prefix = "webpay")
public record WebpayProperties(
        String baseUrl,
        String codigoComercio,
        String apiKey,
        String returnUrl,
        String resultadoUrl,
        Duration timeout) {

    public WebpayProperties {
        baseUrl = requerido("base-url", baseUrl);
        codigoComercio = requerido("codigo-comercio", codigoComercio);
        apiKey = requerido("api-key", apiKey);
        returnUrl = requerido("return-url", returnUrl);
        resultadoUrl = requerido("resultado-url", resultadoUrl);
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        for (String url : new String[] {baseUrl, returnUrl, resultadoUrl}) {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalStateException("webpay: URL invalida " + url);
            }
        }
    }

    private static String requerido(String nombre, String valor) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("webpay." + nombre + " es obligatorio");
        }
        String limpio = valor.trim();
        return limpio.endsWith("/") ? limpio.substring(0, limpio.length() - 1) : limpio;
    }
}
