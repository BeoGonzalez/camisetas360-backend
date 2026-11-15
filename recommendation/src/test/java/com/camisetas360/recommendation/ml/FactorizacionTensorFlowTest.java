package com.camisetas360.recommendation.ml;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Entrena con TensorFlow real (binarios nativos) sobre dos comunidades de usuarios. */
class FactorizacionTensorFlowTest {

    // Productos 0..3: camisetas de Chile. 4..7: Argentina.
    private static final int USUARIOS = 17;
    private static final int PRODUCTOS = 8;
    private static final int NUEVO = 16;

    @Test
    void aprendeDeUsuariosParecidos() {
        List<int[]> pares = new ArrayList<>();
        for (int u = 0; u < 8; u++) {
            for (int i = 0; i < 4; i++) pares.add(new int[]{u, i});         // fans de Chile
            for (int i = 4; i < 8; i++) pares.add(new int[]{u + 8, i});     // fans de Argentina
        }
        pares.add(new int[]{NUEVO, 0}); // el usuario nuevo solo vio dos camisetas de Chile
        pares.add(new int[]{NUEVO, 1});
        int[] u = pares.stream().mapToInt(p -> p[0]).toArray();
        int[] i = pares.stream().mapToInt(p -> p[1]).toArray();
        float[] w = new float[pares.size()];
        java.util.Arrays.fill(w, 2f);

        float[][] afinidad = FactorizacionTensorFlow.entrenar(USUARIOS, PRODUCTOS, u, i, w,
                FactorizacionTensorFlow.Parametros.porDefecto());

        float[] nuevo = afinidad[NUEVO];
        float minChileNoVisto = Math.min(nuevo[2], nuevo[3]);
        float maxArgentina = 0f;
        for (int p = 4; p < 8; p++) maxArgentina = Math.max(maxArgentina, nuevo[p]);
        // Lo que no vio, pero eligieron usuarios parecidos, queda sobre la otra comunidad.
        assertThat(minChileNoVisto).isGreaterThan(maxArgentina);
        assertThat(nuevo[0]).isGreaterThan(0.5f);
        for (float valor : nuevo) assertThat(valor).isBetween(0f, 1f);
    }
}
