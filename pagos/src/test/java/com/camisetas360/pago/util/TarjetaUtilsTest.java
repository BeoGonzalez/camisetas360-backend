package com.camisetas360.pago.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TarjetaUtilsTest {

    private static final String SECRETO = "secreto-de-prueba-antifraude-123";

    @Test
    @DisplayName("Debe sanitizar correctamente espacios y guiones")
    void sanitizarNumero() {
        assertEquals("4532123456789012", TarjetaUtils.sanitizarNumero("4532 1234 5678 9012"));
        assertEquals("4532123456789012", TarjetaUtils.sanitizarNumero("4532-1234-5678-9012"));
    }

    @Test
    @DisplayName("HMAC-SHA256 debe generar hash de 64 caracteres hexadecimales de forma determinista")
    void calcularHmacSha256() {
        String pan = "4532123456789012";
        String hash1 = TarjetaUtils.calcularHmacSha256(pan, SECRETO);
        String hash2 = TarjetaUtils.calcularHmacSha256("4532-1234-5678-9012", SECRETO);

        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2, "Mismo número con diferente formato debe producir el mismo hash");

        String hashDistinto = TarjetaUtils.calcularHmacSha256("4532123456789013", SECRETO);
        assertNotEquals(hash1, hashDistinto);
    }

    @Test
    @DisplayName("Debe extraer los últimos 4 dígitos")
    void extraerUltimos4() {
        assertEquals("9012", TarjetaUtils.extraerUltimos4("4532 1234 5678 9012"));
        assertEquals("9012", TarjetaUtils.extraerUltimos4("4532-1234-5678-9012"));
        assertEquals("12", TarjetaUtils.extraerUltimos4("12"));
    }

    @Test
    @DisplayName("Debe detectar correctamente la marca de la tarjeta")
    void detectarMarca() {
        assertEquals("VISA", TarjetaUtils.detectarMarca("4532 1234 5678 9012"));
        assertEquals("MASTERCARD", TarjetaUtils.detectarMarca("5105 1234 5678 9012"));
        assertEquals("MASTERCARD", TarjetaUtils.detectarMarca("2221 1234 5678 9012"));
        assertEquals("AMEX", TarjetaUtils.detectarMarca("3400 1234 5678 901"));
        assertEquals("AMEX", TarjetaUtils.detectarMarca("3700 1234 5678 901"));
        assertEquals("OTRA", TarjetaUtils.detectarMarca("6011 1234 5678 9012"));
    }
}
