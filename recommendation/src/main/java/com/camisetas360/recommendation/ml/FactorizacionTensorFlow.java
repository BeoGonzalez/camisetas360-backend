package com.camisetas360.recommendation.ml;

import org.tensorflow.Graph;
import org.tensorflow.Operand;
import org.tensorflow.Result;
import org.tensorflow.Session;
import org.tensorflow.framework.optimizers.Adam;
import org.tensorflow.ndarray.Shape;
import org.tensorflow.op.Op;
import org.tensorflow.op.Ops;
import org.tensorflow.op.core.Placeholder;
import org.tensorflow.op.core.Variable;
import org.tensorflow.types.TFloat32;
import org.tensorflow.types.TInt32;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Red de la guia "Recomendaciones Kafka + TensorFlow", con TensorFlow Java dentro del servicio:
 * filtrado colaborativo implicito con embeddings de usuario y producto (factorizacion de matrices).
 *
 * <p>Cada par usuario-producto con interacciones es un positivo, ponderado por su peso acumulado
 * (vista 1, carrito 2, compra 3). En cada epoca se muestrean negativos nuevos entre los productos que
 * el usuario no toco, asi el modelo aprende de usuarios con gustos parecidos. Perdida: entropia
 * cruzada sigmoide ponderada + L2; optimizador Adam.
 */
public final class FactorizacionTensorFlow {

    public record Parametros(int dimension, int epocas, int negativosPorPositivo, float tasaAprendizaje,
                             float l2, long semilla) {
        public static Parametros porDefecto() {
            return new Parametros(16, 40, 4, 0.05f, 1e-4f, 42L);
        }
    }

    private FactorizacionTensorFlow() {
    }

    /**
     * @param usuarios     cantidad de usuarios (indices 0..usuarios-1)
     * @param productos    cantidad de productos (indices 0..productos-1)
     * @param posUsuario   usuario de cada positivo
     * @param posProducto  producto de cada positivo
     * @param posPeso      peso acumulado de cada positivo
     * @return afinidad [usuario][producto] entre 0 y 1
     */
    public static float[][] entrenar(int usuarios, int productos, int[] posUsuario, int[] posProducto,
                                     float[] posPeso, Parametros p) {
        Random random = new Random(p.semilla());
        int[][] noVistos = noVistos(usuarios, productos, posUsuario, posProducto);
        float[] pesoPositivo = new float[posPeso.length];
        for (int n = 0; n < posPeso.length; n++) {
            pesoPositivo[n] = 1f + (float) Math.log1p(posPeso[n]); // mas peso a compras y repeticiones, con saturacion
        }

        try (Graph graph = new Graph(); Session session = new Session(graph)) {
            Ops tf = Ops.create(graph);
            Placeholder<TInt32> usuario = tf.placeholder(TInt32.class, Placeholder.shape(Shape.of(-1)));
            Placeholder<TInt32> producto = tf.placeholder(TInt32.class, Placeholder.shape(Shape.of(-1)));
            Placeholder<TFloat32> etiqueta = tf.placeholder(TFloat32.class, Placeholder.shape(Shape.of(-1)));
            Placeholder<TFloat32> peso = tf.placeholder(TFloat32.class, Placeholder.shape(Shape.of(-1)));

            Variable<TFloat32> embUsuarios = tf.variable(tf.constant(aleatoria(usuarios, p.dimension(), random)));
            Variable<TFloat32> embProductos = tf.variable(tf.constant(aleatoria(productos, p.dimension(), random)));
            Variable<TFloat32> sesgo = tf.variable(tf.constant(new float[productos]));

            Operand<TFloat32> u = tf.gather(embUsuarios, usuario, tf.constant(0));
            Operand<TFloat32> v = tf.gather(embProductos, producto, tf.constant(0));
            Operand<TFloat32> logit = tf.math.add(
                    tf.reduceSum(tf.math.mul(u, v), tf.constant(1)),
                    tf.gather(sesgo, producto, tf.constant(0)));

            // Entropia cruzada sigmoide estable: max(x,0) - x*y + log(1 + e^-|x|)
            Operand<TFloat32> entropia = tf.math.add(
                    tf.math.sub(tf.math.maximum(logit, tf.constant(0f)), tf.math.mul(logit, etiqueta)),
                    tf.math.log1p(tf.math.exp(tf.math.neg(tf.math.abs(logit)))));
            Operand<TFloat32> perdidaDatos = tf.math.div(
                    tf.reduceSum(tf.math.mul(entropia, peso), tf.constant(0)),
                    tf.reduceSum(peso, tf.constant(0)));
            Operand<TFloat32> regularizacion = tf.math.mul(tf.constant(p.l2()), tf.math.add(
                    tf.reduceSum(tf.math.square(u), tf.constant(new int[]{0, 1})),
                    tf.reduceSum(tf.math.square(v), tf.constant(new int[]{0, 1}))));
            Op entrenamiento = new Adam(graph, p.tasaAprendizaje()).minimize(tf.math.add(perdidaDatos, regularizacion));

            session.initialize();

            for (int epoca = 0; epoca < p.epocas(); epoca++) {
                Lote lote = lote(posUsuario, posProducto, pesoPositivo, noVistos, p.negativosPorPositivo(), random);
                int tamano = lote.usuarios.length > 20_000 ? 256 : 64;
                for (int desde = 0; desde < lote.usuarios.length; desde += tamano) {
                    int hasta = Math.min(desde + tamano, lote.usuarios.length);
                    try (TInt32 tu = TInt32.vectorOf(java.util.Arrays.copyOfRange(lote.usuarios, desde, hasta));
                         TInt32 ti = TInt32.vectorOf(java.util.Arrays.copyOfRange(lote.productos, desde, hasta));
                         TFloat32 ty = TFloat32.vectorOf(java.util.Arrays.copyOfRange(lote.etiquetas, desde, hasta));
                         TFloat32 tw = TFloat32.vectorOf(java.util.Arrays.copyOfRange(lote.pesos, desde, hasta))) {
                        session.runner()
                                .feed(usuario, tu).feed(producto, ti).feed(etiqueta, ty).feed(peso, tw)
                                .addTarget(entrenamiento)
                                .run()
                                .close();
                    }
                }
            }

            try (Result pesos = session.runner().fetch(embUsuarios).fetch(embProductos).fetch(sesgo).run()) {
                return afinidad((TFloat32) pesos.get(0), (TFloat32) pesos.get(1), (TFloat32) pesos.get(2),
                        usuarios, productos, p.dimension());
            }
        }
    }

    private static float[][] afinidad(TFloat32 usuarios, TFloat32 productos, TFloat32 sesgo,
                                      int nUsuarios, int nProductos, int dimension) {
        float[][] u = matriz(usuarios, nUsuarios, dimension);
        float[][] v = matriz(productos, nProductos, dimension);
        float[][] afinidad = new float[nUsuarios][nProductos];
        for (int a = 0; a < nUsuarios; a++) {
            for (int b = 0; b < nProductos; b++) {
                double x = sesgo.getFloat(b);
                for (int k = 0; k < dimension; k++) {
                    x += u[a][k] * v[b][k];
                }
                afinidad[a][b] = (float) (1.0 / (1.0 + Math.exp(-x)));
            }
        }
        return afinidad;
    }

    private static float[][] matriz(TFloat32 tensor, int filas, int columnas) {
        float[][] m = new float[filas][columnas];
        for (int f = 0; f < filas; f++) {
            for (int c = 0; c < columnas; c++) {
                m[f][c] = tensor.getFloat(f, c);
            }
        }
        return m;
    }

    private static float[][] aleatoria(int filas, int columnas, Random random) {
        float[][] m = new float[filas][columnas];
        for (float[] fila : m) {
            for (int c = 0; c < columnas; c++) {
                fila[c] = (float) (random.nextGaussian() * 0.1);
            }
        }
        return m;
    }

    private static int[][] noVistos(int usuarios, int productos, int[] posUsuario, int[] posProducto) {
        List<Set<Integer>> vistos = new ArrayList<>();
        for (int u = 0; u < usuarios; u++) vistos.add(new HashSet<>());
        for (int n = 0; n < posUsuario.length; n++) vistos.get(posUsuario[n]).add(posProducto[n]);
        int[][] resultado = new int[usuarios][];
        for (int u = 0; u < usuarios; u++) {
            Set<Integer> vistosU = vistos.get(u);
            resultado[u] = java.util.stream.IntStream.range(0, productos).filter(i -> !vistosU.contains(i)).toArray();
        }
        return resultado;
    }

    /** Positivos + negativos recien muestreados, mezclados. */
    private static Lote lote(int[] posUsuario, int[] posProducto, float[] pesoPositivo, int[][] noVistos,
                             int negativos, Random random) {
        int total = posUsuario.length;
        for (int u : posUsuario) {
            if (noVistos[u].length > 0) total += negativos;
        }
        var lote = new Lote(total);
        int n = 0;
        for (int k = 0; k < posUsuario.length; k++) {
            lote.poner(n++, posUsuario[k], posProducto[k], 1f, pesoPositivo[k]);
            int[] candidatos = noVistos[posUsuario[k]];
            if (candidatos.length == 0) continue;
            for (int j = 0; j < negativos; j++) {
                lote.poner(n++, posUsuario[k], candidatos[random.nextInt(candidatos.length)], 0f, 1f);
            }
        }
        for (int i = total - 1; i > 0; i--) {
            lote.intercambiar(i, random.nextInt(i + 1));
        }
        return lote;
    }

    private static final class Lote {
        final int[] usuarios;
        final int[] productos;
        final float[] etiquetas;
        final float[] pesos;

        Lote(int tamano) {
            usuarios = new int[tamano];
            productos = new int[tamano];
            etiquetas = new float[tamano];
            pesos = new float[tamano];
        }

        void poner(int n, int u, int p, float y, float w) {
            usuarios[n] = u;
            productos[n] = p;
            etiquetas[n] = y;
            pesos[n] = w;
        }

        void intercambiar(int a, int b) {
            int iu = usuarios[a]; usuarios[a] = usuarios[b]; usuarios[b] = iu;
            int ip = productos[a]; productos[a] = productos[b]; productos[b] = ip;
            float fy = etiquetas[a]; etiquetas[a] = etiquetas[b]; etiquetas[b] = fy;
            float fw = pesos[a]; pesos[a] = pesos[b]; pesos[b] = fw;
        }
    }
}
