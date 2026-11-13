package com.camisetas360.pago.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Errores en formato RFC 7807 (application/problem+json). Se conservan como propiedades
 * adicionales los campos legados (timestamp, error, mensaje, ...) para no romper a los clientes actuales.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FraudeDetectadoException.class)
    public ResponseEntity<ProblemDetail> handleFraudeDetectado(FraudeDetectadoException ex) {
        log.warn("Respondiendo 429 Antifraude: ordenId={}, ultimos4={}", ex.getOrdenId(), ex.getTarjetaUltimos4());
        ProblemDetail problem = problema(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", ex.getMessage());
        problem.setProperty("codigo", "BLOQUEADO_FRAUDE");
        problem.setProperty("ordenId", ex.getOrdenId());
        problem.setProperty("pagoId", ex.getPagoId());
        problem.setProperty("tarjetaUltimos4", ex.getTarjetaUltimos4());
        problem.setProperty("intentosDetectados", ex.getIntentos());
        problem.setProperty("ventanaSegundos", ex.getVentanaSegundos());
        return responder(problem);
    }

    @ExceptionHandler(PagoNotFoundException.class)
    public ResponseEntity<ProblemDetail> handlePagoNotFound(PagoNotFoundException ex) {
        return responder(problema(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(PagoInvalidoException.class)
    public ResponseEntity<ProblemDetail> handlePagoInvalido(PagoInvalidoException ex) {
        return responder(problema(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return responder(problema(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleCuerpoIlegible(HttpMessageNotReadableException ex) {
        return responder(problema(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "El cuerpo de la solicitud no es un JSON válido"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTipoInvalido(MethodArgumentTypeMismatchException ex) {
        return responder(problema(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Valor inválido para el parámetro '" + ex.getName() + "'"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex) {
        // Excepciones del framework que ya conocen su estado HTTP (404 ruta, 405, 415, parámetro faltante...).
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            HttpStatus resuelto = HttpStatus.resolve(status.value());
            String error = resuelto != null ? resuelto.name() : String.valueOf(status.value());
            return responder(problema(status, error, errorResponse.getBody().getDetail()));
        }
        log.error("Error no controlado en ms-pago: ", ex);
        return responder(problema(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "Ocurrió un error interno procesando la solicitud"));
    }

    private static ProblemDetail problema(HttpStatusCode status, String error, String mensaje) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, mensaje);
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("error", error);
        problem.setProperty("mensaje", mensaje);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> responder(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
