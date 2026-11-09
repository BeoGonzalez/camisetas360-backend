package com.camisetas360.pago.port;

public interface PasarelaPagoPort {
    IniciarPagoResult iniciarTransaccion(IniciarPagoComando comando);
    ConfirmarPagoResult confirmarTransaccion(String token, String tarjetaUltimos4);
}
