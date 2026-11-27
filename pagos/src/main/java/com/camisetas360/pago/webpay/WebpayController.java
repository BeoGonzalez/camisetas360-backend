package com.camisetas360.pago.webpay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import com.camisetas360.pago.security.EntraIdentity;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Pago con Webpay Plus:
 * <ol>
 *   <li>{@code POST /api/v1/pago/webpay/iniciar} devuelve {@code token} y {@code url}; el frontend envia el
 *       navegador a {@code url} con {@code token_ws}.</li>
 *   <li>Webpay devuelve al cliente a {@code /api/v1/pago/webpay/retorno} (GET o POST, segun el caso), que
 *       confirma el pago y redirige a la pagina de resultado del frontend con {@code estado=APROBADO|RECHAZADO}.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/pago/webpay")
public class WebpayController {

    private final WebpayPagoService service;
    private final WebpayProperties properties;

    public WebpayController(WebpayPagoService service, WebpayProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    public record IniciarWebpayRequest(
            @NotNull(message = "El id de orden es obligatorio") UUID ordenId,
            @NotNull(message = "El monto es obligatorio")
            @DecimalMin(value = "1.0", message = "El monto debe ser mayor a 0") BigDecimal monto) {
    }

    @PostMapping("/iniciar")
    public ResponseEntity<WebpayPagoService.Inicio> iniciar(@Valid @RequestBody IniciarWebpayRequest request,
                                                            @AuthenticationPrincipal Jwt jwt) {
        var identidad = EntraIdentity.from(jwt);
        String usuarioId = identidad.tenantId() + ":" + identidad.objectId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.iniciar(usuarioId, request.ordenId(), request.monto()));
    }

    @RequestMapping(value = "/retorno", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Void> retorno(
            @RequestParam(name = "token_ws", required = false) String tokenWs,
            @RequestParam(name = "TBK_TOKEN", required = false) String tbkToken,
            @RequestParam(name = "TBK_ORDEN_COMPRA", required = false) String tbkOrdenCompra) {
        var retorno = service.procesarRetorno(tokenWs, tbkToken, tbkOrdenCompra);
        var destino = UriComponentsBuilder.fromUriString(properties.resultadoUrl())
                .queryParam("estado", retorno.aprobado() ? "APROBADO" : "RECHAZADO");
        retorno.pago().ifPresent(pago -> destino
                .queryParam("pagoId", pago.getId())
                .queryParam("ordenId", pago.getOrdenId()));
        URI location = destino.build().toUri();
        // 303: el navegador sigue con GET aunque Webpay haya vuelto por POST.
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, location.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
