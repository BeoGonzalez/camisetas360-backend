package com.camisetas360.pago.webpay;

/**
 * Operaciones de Webpay Plus que usa el flujo de pago con redireccion: el cliente ingresa la tarjeta en
 * la pagina de Transbank, nunca en Camisetas360.
 */
public interface WebpayPlusPort {

    /** Crea la transaccion; el navegador debe ir a {@code url} con {@code token_ws=token}. */
    Transaccion crear(String ordenCompra, String sesionId, long monto, String returnUrl);

    /** Confirma (commit) la transaccion cuando Webpay devuelve al cliente con {@code token_ws}. */
    Resultado confirmar(String token);

    /** Estado de una transaccion (sirve si el commit fallo por red y hay que saber como quedo). */
    Resultado estado(String token);

    record Transaccion(String token, String url) {
    }

    /**
     * @param aprobada          {@code status=AUTHORIZED} y {@code response_code=0}
     * @param estado            status de Transbank (AUTHORIZED, FAILED, INITIALIZED, ...)
     * @param ultimos4          ultimos digitos de la tarjeta informados por Transbank (puede ser null)
     * @param tipoPago          payment_type_code (VD debito, VN credito, ...)
     */
    record Resultado(
            boolean aprobada,
            String estado,
            Integer codigoRespuesta,
            String codigoAutorizacion,
            String ultimos4,
            String ordenCompra,
            Long monto,
            String tipoPago) {
    }

    /** Error al comunicarse con Transbank o respuesta no valida. */
    class WebpayException extends RuntimeException {
        private final int status;

        public WebpayException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        /** Codigo HTTP de Transbank, o 0 si no hubo respuesta. */
        public int getStatus() {
            return status;
        }
    }
}
