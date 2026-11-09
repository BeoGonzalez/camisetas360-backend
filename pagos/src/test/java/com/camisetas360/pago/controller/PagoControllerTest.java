package com.camisetas360.pago.controller;

import com.camisetas360.pago.dto.ConfirmarPagoRequest;
import com.camisetas360.pago.dto.IniciarPagoRequest;
import com.camisetas360.pago.dto.IniciarPagoResponse;
import com.camisetas360.pago.dto.PagoResponseDTO;
import com.camisetas360.pago.exception.FraudeDetectadoException;
import com.camisetas360.pago.exception.GlobalExceptionHandler;
import com.camisetas360.pago.exception.PagoNotFoundException;
import com.camisetas360.pago.model.EstadoPago;
import com.camisetas360.pago.service.PagoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PagoController.class)
@Import(GlobalExceptionHandler.class)
class PagoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    private PagoService pagoService;

    @Test
    @DisplayName("POST /api/v1/pago/iniciar retorna 201 Created cuando es exitoso")
    void iniciarPagoExitoso() throws Exception {
        UUID ordenId = UUID.randomUUID();
        UUID pagoId = UUID.randomUUID();

        IniciarPagoRequest request = new IniciarPagoRequest(
                ordenId,
                BigDecimal.valueOf(15000),
                "4532123456789012",
                "123",
                10,
                2026,
                "Cliente Test"
        );

        IniciarPagoResponse response = new IniciarPagoResponse(
                pagoId,
                ordenId,
                BigDecimal.valueOf(15000),
                "INICIADO",
                "tok_test_123",
                "http://localhost:8087/api/v1/pago/retorno?token_ws=tok_test_123",
                "9012",
                "VISA"
        );

        when(pagoService.iniciarPago(any(IniciarPagoRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/pago/iniciar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pagoId").value(pagoId.toString()))
                .andExpect(jsonPath("$.ordenId").value(ordenId.toString()))
                .andExpect(jsonPath("$.estado").value("INICIADO"))
                .andExpect(jsonPath("$.token").value("tok_test_123"))
                .andExpect(jsonPath("$.tarjetaUltimos4").value("9012"));
    }

    @Test
    @DisplayName("POST /api/v1/pago/iniciar retorna 429 Too Many Requests cuando se detecta fraude")
    void iniciarPagoBloqueadoRetorna429() throws Exception {
        UUID ordenId = UUID.randomUUID();
        UUID pagoId = UUID.randomUUID();

        IniciarPagoRequest request = new IniciarPagoRequest(
                ordenId,
                BigDecimal.valueOf(15000),
                "4532123456789012",
                "123",
                10,
                2026,
                "Cliente Test"
        );

        when(pagoService.iniciarPago(any(IniciarPagoRequest.class))).thenThrow(
                new FraudeDetectadoException(
                        "Bloqueado preventivamente por antifraude: 4 intentos en ventana de 10 segundos",
                        ordenId,
                        pagoId,
                        "hash-dummy",
                        "9012",
                        4,
                        10
                )
        );

        mockMvc.perform(post("/api/v1/pago/iniciar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.codigo").value("BLOQUEADO_FRAUDE"))
                .andExpect(jsonPath("$.ordenId").value(ordenId.toString()))
                .andExpect(jsonPath("$.pagoId").value(pagoId.toString()))
                .andExpect(jsonPath("$.tarjetaUltimos4").value("9012"))
                .andExpect(jsonPath("$.intentosDetectados").value(4))
                .andExpect(jsonPath("$.ventanaSegundos").value(10));
    }

    @Test
    @DisplayName("POST /api/v1/pago/confirmar retorna 200 OK con pago confirmado")
    void confirmarPagoExitoso() throws Exception {
        UUID ordenId = UUID.randomUUID();
        UUID pagoId = UUID.randomUUID();

        ConfirmarPagoRequest request = new ConfirmarPagoRequest("tok_test_123");

        PagoResponseDTO response = new PagoResponseDTO(
                pagoId,
                ordenId,
                BigDecimal.valueOf(15000),
                "CLP",
                EstadoPago.AUTORIZADO,
                "tok_test_123",
                "AUTH-123456",
                "9012",
                "VISA",
                "Aprobada",
                Instant.now(),
                Instant.now()
        );

        when(pagoService.confirmarPago("tok_test_123")).thenReturn(response);

        mockMvc.perform(post("/api/v1/pago/confirmar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("AUTORIZADO"))
                .andExpect(jsonPath("$.codigoAutorizacion").value("AUTH-123456"));
    }

    @Test
    @DisplayName("GET /api/v1/pago/{id} retorna 200 OK o 404 si no existe")
    void obtenerPorId() throws Exception {
        UUID pagoId = UUID.randomUUID();
        UUID ordenId = UUID.randomUUID();

        PagoResponseDTO response = new PagoResponseDTO(
                pagoId,
                ordenId,
                BigDecimal.valueOf(15000),
                "CLP",
                EstadoPago.INICIADO,
                "tok_123",
                null,
                "9012",
                "VISA",
                null,
                Instant.now(),
                Instant.now()
        );

        when(pagoService.obtenerPagoPorId(pagoId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/pago/" + pagoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pagoId.toString()));

        UUID inexistente = UUID.randomUUID();
        when(pagoService.obtenerPagoPorId(inexistente)).thenThrow(new PagoNotFoundException("No encontrado"));

        mockMvc.perform(get("/api/v1/pago/" + inexistente))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
