package com.camisetas360.pago.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TarjetaUtils {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private TarjetaUtils() {}

    public static String sanitizarNumero(String numeroTarjeta) {
        if (numeroTarjeta == null) {
            return "";
        }
        return numeroTarjeta.replaceAll("[\\s-]", "");
    }

    public static String calcularHmacSha256(String numeroTarjeta, String secreto) {
        String panLimpio = sanitizarNumero(numeroTarjeta);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(panLimpio.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Error al calcular HMAC-SHA256 de tarjeta", e);
        }
    }

    public static String extraerUltimos4(String numeroTarjeta) {
        String panLimpio = sanitizarNumero(numeroTarjeta);
        if (panLimpio.length() <= 4) {
            return panLimpio;
        }
        return panLimpio.substring(panLimpio.length() - 4);
    }

    public static String detectarMarca(String numeroTarjeta) {
        String pan = sanitizarNumero(numeroTarjeta);
        if (pan.isEmpty()) {
            return "DESCONOCIDA";
        }
        if (pan.startsWith("4")) {
            return "VISA";
        }
        if (pan.matches("^(5[1-5]|2[2-7]).*")) {
            return "MASTERCARD";
        }
        if (pan.matches("^(34|37).*")) {
            return "AMEX";
        }
        return "OTRA";
    }
}
