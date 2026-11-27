package com.camisetas360.pago.adapter;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Antifraude del boton "Pagar con Webpay": una segunda instancia de la misma ventana deslizante, con su
 * propia regla. Como en Webpay no hay tarjeta antes de pagar, el limite se aplica por usuario autenticado.
 *
 * <p>Por defecto: con {@code max-intentos=1}, la segunda llamada dentro de {@code ventana-segundos} (10 s)
 * bloquea al usuario {@code bloqueo-minutos} (30 min). Los intentos durante el bloqueo no lo alargan.
 */
@Configuration
public class FraudeWebpayConfig {

    public static final String BEAN = "fraudeWebpay";

    @Bean(BEAN)
    public InMemorySlidingWindowFraudeDetector fraudeWebpay(
            @Value("${antifraude.webpay.max-intentos:1}") int maxIntentos,
            @Value("${antifraude.webpay.ventana-segundos:10}") int ventanaSegundos,
            @Value("${antifraude.webpay.bloqueo-minutos:30}") int bloqueoMinutos) {
        return new InMemorySlidingWindowFraudeDetector(maxIntentos, ventanaSegundos, bloqueoMinutos, Clock.systemUTC());
    }
}
