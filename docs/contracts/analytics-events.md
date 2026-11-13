# Contrato de eventos de analítica (Apache Kafka)

Eventos asíncronos que `catalog` y `carrito` publican cada vez que un usuario interactúa con la tienda, y que consume `analytics` (ms-analytics-recommendations). El checkout carrito → orders **no** forma parte de este contrato: sigue en RabbitMQ.

## Tópicos

| Tópico | Productor | `eventType` | Cuándo |
|---|---|---|---|
| `catalogo.producto-visto` | catalog | `PRODUCTO_VISTO` | `GET /api/v1/catalog/products/{sku}` responde 200 |
| `carrito.item-agregado` | carrito | `ITEM_AGREGADO` | `POST /api/v1/carrito/items` |
| `carrito.item-eliminado` | carrito | `ITEM_ELIMINADO` | `DELETE /api/v1/carrito/items/{sku}` |
| `carrito.envio-actualizado` | carrito | `ENVIO_ACTUALIZADO` | `PUT /api/v1/carrito/envio` |
| `pago.fraude-detectado` | pagos | `PAGO_FRAUDE_DETECTADO` | Ver [pago-events.md](pago-events.md); analytics lo usa solo para las alertas del dashboard |

- **Clave de partición:** `usuarioId`. Así los eventos de un mismo usuario llegan en orden.
- **Particiones:** 3 por tópico, replication factor 1 (un broker en ECS / Compose). `auto.create.topics.enable=false`: los tópicos los crea `kafka-init` en local y deben crearse en el broker de AWS.
- **Entrega:** *at-least-once*. Los productores usan `acks=all` e idempotencia. analytics deduplica por `eventId`.
- **Disponibilidad:** publicar es *best effort*. Si Kafka no responde en 1 s, la petición del usuario igual se completa y el fallo queda en el log. carrito publica solo **después del commit** de la transacción.
- **Consumidores:** grupo `analytics-recommendations` (interacciones) y grupo `analytics-dashboard` (fraude) en analytics; grupo `recommendation-interacciones` en recommendation (solo `catalogo.producto-visto` y `carrito.item-agregado`, para su Top 5 ponderado). Si recommendation no puede registrar una interacción (base caída tras ~35 s de reintentos, o datos que la base rechaza), la publica sin cambios en `recommendation.interacciones.dlt` con las cabeceras `kafka_dlt-*` de Spring Kafka; no se pierde.

## Payload (versión 1)

```json
{
  "eventId": "c8e76a6b-2856-4b89-a477-85b88c0378bc",
  "eventType": "ITEM_AGREGADO",
  "version": 1,
  "occurredAt": "2026-11-10T12:00:00Z",
  "source": "carrito",
  "usuarioId": "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222",
  "producto": { "sku": "CHI-LOC", "nombre": "Camiseta Chile Local 2026", "categoria": "Selecciones", "precio": 29990.0 },
  "cantidad": 2,
  "envio": null
}
```

| Campo | Tipo | Regla |
|---|---|---|
| `eventId` | UUID | Obligatorio y único; se usa para deduplicar |
| `eventType` | string | `PRODUCTO_VISTO`, `ITEM_AGREGADO`, `ITEM_ELIMINADO`, `ENVIO_ACTUALIZADO` |
| `version` | int | `1` |
| `occurredAt` | ISO-8601 UTC | Obligatorio |
| `source` | string | `catalog` o `carrito` |
| `usuarioId` | string | `<tid>:<oid>` de Entra en minúsculas. Nunca email ni nombre |
| `producto` | objeto | Obligatorio en `PRODUCTO_VISTO` e `ITEM_*`. `sku` cumple `[A-Za-z0-9._-]{1,64}` |
| `cantidad` | int ≥ 1 | Obligatorio en `ITEM_*`: unidades agregadas o eliminadas |
| `envio` | objeto | Obligatorio en `ENVIO_ACTUALIZADO`: `metodo` (`STANDARD`, `EXPRESS`, `RETIRO_TIENDA`) y `region`. No incluye comuna ni dirección |

analytics ignora los campos desconocidos. Los mensajes inválidos se registran y se descartan, sin reintentarse.

El record `InteraccionEvento` está **duplicado** en `catalog`, `carrito`, `analytics` y `recommendation`: cualquier cambio del contrato debe aplicarse en las cuatro copias y en este documento.

## Persistencia en DynamoDB (analytics)

| Tabla | Clave | Contenido |
|---|---|---|
| `camisetas360-analytics-eventos` | `usuarioId` + `sk` (`occurredAt#eventId`) | Historial de navegación; TTL de 90 días en `expiresAt` |
| `camisetas360-analytics-productos` | `sku` | Ficha conocida del producto y contadores `vistas` / `agregados` |
| `camisetas360-analytics-recomendaciones` | `usuarioId` | Recomendaciones vigentes y perfil de navegación (afinidad por categoría) |

Las tablas son *on-demand* (`PAY_PER_REQUEST`). analytics las crea al arrancar si no existen (`DYNAMODB_CREATE_TABLES=true`).

## API y WebSocket de analytics

| Endpoint | Permiso | Respuesta |
|---|---|---|
| `GET /api/v1/analytics/recomendaciones` | rol `CUSTOMER` + scope `Catalog.Read` | Recomendaciones y perfil del usuario del token |
| `GET /api/v1/analytics/metricas` | rol `ADMIN` o `CATALOG_MANAGER` + scope `Catalog.Read` | Snapshot de métricas (carga inicial del dashboard) |
| `/ws/metricas` (STOMP) · `/ws/metricas-sockjs` (SockJS) | Token en el frame `CONNECT` (`Authorization: Bearer …`), mismo permiso que `/metricas` | Suscripción a `/topic/metricas`: snapshot cada 2 s |

Métricas del snapshot: `clicsUltimoMinuto`, `usuariosActivos` (5 min), `serieClics` (por minuto, 15 min), `productosMasVistos` (top 5, 1 h), `eventosPorTipo` (1 h) y `alertas` (`FRAUDE`, `NAVEGACION_ANOMALA` con más de 20 interacciones de un usuario en 10 s, `PICO_TRAFICO` con más de 60 interacciones por minuto). Las métricas viven en memoria de la tarea; con una sola tarea ECS reflejan todo el tráfico.

## Persistencia en DynamoDB (recommendation)

recommendation copia cada interacción que registra (vistas y carrito por Kafka, compras por RabbitMQ) en la tabla de la guía *Recomendaciones Kafka + TensorFlow*, que lee el servicio de TensorFlow:

| Tabla | Clave | Atributos |
|---|---|---|
| `UserInteractions` (`DYNAMODB_TABLA_INTERACCIONES`) | `userId` (`<tid>:<oid>`) + `timestamp` (`2026-11-10T12:00:00.000Z#<eventId>`), ambas String | `itemId` (sku), `action` (`view` / `cart` / `purchase`), `weight` (1 / 2 / 3), `category`, `name`, `price` (si se conocen), `source`, `eventId`, `occurredAt`, `occurredAtEpochMs` |

La escritura es asíncrona y no pierde datos: PostgreSQL es la fuente de verdad y las columnas `dynamo_*` de `interacciones` funcionan como outbox. Reenviar una interacción sobrescribe el mismo ítem, porque la clave deriva del `eventId`.
