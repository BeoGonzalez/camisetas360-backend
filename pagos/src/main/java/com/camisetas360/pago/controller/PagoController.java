package com.camisetas360.pago.controller;

import com.camisetas360.pago.dto.AlertaFraudeResponseDTO;
import com.camisetas360.pago.dto.ConfirmarPagoRequest;
import com.camisetas360.pago.dto.IniciarPagoRequest;
import com.camisetas360.pago.dto.IniciarPagoResponse;
import com.camisetas360.pago.dto.PagoResponseDTO;
import com.camisetas360.pago.service.PagoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pago")
public class PagoController {

    private final PagoService pagoService;

    public PagoController(PagoService pagoService) {
        this.pagoService = pagoService;
    }

    @PostMapping("/iniciar")
    public ResponseEntity<IniciarPagoResponse> iniciarPago(@Valid @RequestBody IniciarPagoRequest request) {
        IniciarPagoResponse response = pagoService.iniciarPago(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/confirmar")
    public ResponseEntity<PagoResponseDTO> confirmarPago(@Valid @RequestBody ConfirmarPagoRequest request) {
        PagoResponseDTO response = pagoService.confirmarPago(request.token());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/retorno")
    public ResponseEntity<PagoResponseDTO> retornoWebpay(@RequestParam("token_ws") String tokenWs) {
        PagoResponseDTO response = pagoService.confirmarPago(tokenWs);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PagoResponseDTO> obtenerPorId(@PathVariable("id") UUID id) {
        PagoResponseDTO response = pagoService.obtenerPagoPorId(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/orden/{ordenId}")
    public ResponseEntity<List<PagoResponseDTO>> obtenerPorOrden(@PathVariable("ordenId") UUID ordenId) {
        List<PagoResponseDTO> response = pagoService.obtenerPagosPorOrden(ordenId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/alertas")
    public ResponseEntity<List<AlertaFraudeResponseDTO>> obtenerAlertas() {
        List<AlertaFraudeResponseDTO> alertas = pagoService.obtenerAlertasFraude();
        return ResponseEntity.ok(alertas);
    }
}
