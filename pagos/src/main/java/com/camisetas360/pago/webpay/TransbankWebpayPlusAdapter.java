package com.camisetas360.pago.webpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cliente de la API REST de Webpay Plus v1.2 de Transbank.
 * https://www.transbankdevelopers.cl/referencia/webpay#webpay-plus
 */
public class TransbankWebpayPlusAdapter implements WebpayPlusPort {

    static final String TRANSACCIONES = "/rswebpaytransaction/api/webpay/v1.2/transactions";

    private static final Logger log = LoggerFactory.getLogger(TransbankWebpayPlusAdapter.class);

    private final RestClient rest;
    private final ObjectMapper json;

    /** @param rest cliente con la base URL de Transbank y los headers Tbk-Api-Key-Id / Tbk-Api-Key-Secret. */
    public TransbankWebpayPlusAdapter(RestClient rest, ObjectMapper json) {
        this.rest = rest;
        this.json = json;
    }

    @Override
    public Transaccion crear(String ordenCompra, String sesionId, long monto, String returnUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("buy_order", ordenCompra);
        body.put("session_id", sesionId);
        body.put("amount", monto);
        body.put("return_url", returnUrl);
        JsonNode respuesta = llamar("crear", () -> rest.post().uri(TRANSACCIONES)
                .contentType(MediaType.APPLICATION_JSON)
                .body(escribir(body))
                .retrieve().body(String.class));
        String token = texto(respuesta, "token");
        String url = texto(respuesta, "url");
        if (token == null || url == null) {
            throw new WebpayException("Transbank no devolvio token/url", 0, null);
        }
        log.info("Webpay Plus: transaccion creada para buy_order {}", ordenCompra);
        return new Transaccion(token, url);
    }

    @Override
    public Resultado confirmar(String token) {
        return resultado(llamar("confirmar", () -> rest.put().uri(TRANSACCIONES + "/{token}", token)
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve().body(String.class)));
    }

    @Override
    public Resultado estado(String token) {
        return resultado(llamar("estado", () -> rest.get().uri(TRANSACCIONES + "/{token}", token)
                .retrieve().body(String.class)));
    }

    private Resultado resultado(JsonNode r) {
        String estado = texto(r, "status");
        Integer codigo = r.hasNonNull("response_code") ? r.get("response_code").asInt() : null;
        boolean aprobada = "AUTHORIZED".equals(estado) && Integer.valueOf(0).equals(codigo);
        JsonNode tarjeta = r.path("card_detail");
        return new Resultado(
                aprobada,
                estado,
                codigo,
                texto(r, "authorization_code"),
                tarjeta.isObject() ? texto(tarjeta, "card_number") : null,
                texto(r, "buy_order"),
                r.hasNonNull("amount") ? r.get("amount").asLong() : null,
                texto(r, "payment_type_code"));
    }

    private JsonNode llamar(String operacion, java.util.function.Supplier<String> llamada) {
        try {
            String cuerpo = llamada.get();
            return json.readTree(cuerpo == null || cuerpo.isBlank() ? "{}" : cuerpo);
        } catch (RestClientResponseException e) {
            // Transbank responde {"error_message": "..."}; nunca contiene datos de tarjeta.
            log.warn("Webpay Plus: {} fallo con HTTP {}: {}", operacion, e.getStatusCode().value(),
                    e.getResponseBodyAsString());
            throw new WebpayException("Transbank rechazo la operacion " + operacion, e.getStatusCode().value(), e);
        } catch (WebpayException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Webpay Plus: {} fallo: {}", operacion, e.getClass().getSimpleName());
            throw new WebpayException("No se pudo comunicar con Transbank (" + operacion + ")", 0, e);
        }
    }

    private String escribir(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new WebpayException("No se pudo serializar la solicitud", 0, e);
        }
    }

    private static String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        return valor == null || valor.isNull() ? null : valor.asText();
    }
}
