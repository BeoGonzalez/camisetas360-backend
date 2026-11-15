package com.camisetas360.analytics.repository;

import com.camisetas360.analytics.model.Recomendacion;
import com.camisetas360.analytics.model.RecomendacionesGuardadas;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.camisetas360.analytics.repository.EventoRepository.n;
import static com.camisetas360.analytics.repository.EventoRepository.s;

/** Recomendaciones y perfil de navegacion vigentes de cada usuario (tabla recomendaciones). */
@Repository
public class RecomendacionRepository {

    private final DynamoDbClient client;
    private final DynamoDbTables tables;

    public RecomendacionRepository(DynamoDbClient client, DynamoDbTables tables) {
        this.client = client;
        this.tables = tables;
    }

    public void guardar(String usuarioId, List<Recomendacion> items, Map<String, Double> perfil, Instant generadoEn) {
        List<AttributeValue> lista = items.stream().map(RecomendacionRepository::aAtributo).toList();
        // El orden del perfil se conserva en una lista (los mapas de DynamoDB no tienen orden).
        List<AttributeValue> categorias = perfil.entrySet().stream()
                .map(entry -> AttributeValue.builder().m(Map.of(
                        "categoria", s(entry.getKey()),
                        "afinidad", n(entry.getValue()))).build())
                .toList();

        client.putItem(request -> request.tableName(tables.recomendaciones()).item(Map.of(
                "usuarioId", s(usuarioId),
                "items", AttributeValue.builder().l(lista).build(),
                "perfil", AttributeValue.builder().l(categorias).build(),
                "generadoEn", s(generadoEn.toString()))));
    }

    public Optional<RecomendacionesGuardadas> buscar(String usuarioId) {
        var response = client.getItem(request -> request.tableName(tables.recomendaciones())
                .key(Map.of("usuarioId", s(usuarioId))));
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        var item = response.item();
        List<Recomendacion> items = item.containsKey("items")
                ? item.get("items").l().stream().map(value -> aRecomendacion(value.m())).toList()
                : List.of();
        Map<String, Double> perfil = new LinkedHashMap<>();
        if (item.containsKey("perfil")) {
            item.get("perfil").l().forEach(value ->
                    perfil.put(value.m().get("categoria").s(), Double.valueOf(value.m().get("afinidad").n())));
        }
        return Optional.of(new RecomendacionesGuardadas(items, perfil,
                item.containsKey("generadoEn") ? item.get("generadoEn").s() : null));
    }

    private static AttributeValue aAtributo(Recomendacion recomendacion) {
        Map<String, AttributeValue> valores = new HashMap<>();
        valores.put("sku", s(recomendacion.sku()));
        valores.put("score", n(recomendacion.score()));
        valores.put("motivo", s(recomendacion.motivo()));
        if (recomendacion.nombre() != null) {
            valores.put("nombre", s(recomendacion.nombre()));
        }
        if (recomendacion.categoria() != null) {
            valores.put("categoria", s(recomendacion.categoria()));
        }
        if (recomendacion.precio() != null) {
            valores.put("precio", n(recomendacion.precio()));
        }
        return AttributeValue.builder().m(valores).build();
    }

    private static Recomendacion aRecomendacion(Map<String, AttributeValue> valores) {
        return new Recomendacion(
                valores.get("sku").s(),
                valores.containsKey("nombre") ? valores.get("nombre").s() : null,
                valores.containsKey("categoria") ? valores.get("categoria").s() : null,
                valores.containsKey("precio") ? Double.valueOf(valores.get("precio").n()) : null,
                Double.parseDouble(valores.get("score").n()),
                valores.get("motivo").s());
    }
}
