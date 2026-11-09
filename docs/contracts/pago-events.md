# Contrato de Eventos de Pago - Apache Kafka

Este documento define el contrato formal de mensajería asíncrona publicado por el microservicio `pagos` y destinado a consumidores del ecosistema, especialmente `ms-analytics-recommendations` y `ms-orders`.

---

## 1. Topología de Tópicos

| Tópico | Clave de Partición | Semántica | Productor |
|---|---|---|---|
| `pago.intento-registrado` | `ordenId` (UUID) | Se dispara cuando un cliente inicia una transacción de pago válida en Webpay. | `ms-pago` |
| `pago.autorizado` | `ordenId` (UUID) | Se dispara cuando la pasarela de pagos aprueba la transacción exitosamente. | `ms-pago` |
| `pago.rechazado` | `ordenId` (UUID) | Se dispara cuando la pasarela rechaza la transacción (fondos insuficientes, tarjeta expirada, etc.). | `ms-pago` |
| `pago.fraude-detectado` | `ordenId` (UUID) | Se dispara cuando el motor de reglas antifraude bloquea preventivamente la transacción. | `ms-pago` |

---

## 2. Garantías de Entrega y Producción

- **Patrón Outbox**: Los eventos se persisten en la tabla `pago.outbox_evento` en la misma transacción ACID que actualiza la entidad `pago` y `alerta_fraude`. Un relay programado publica hacia Kafka.
- **Confiabilidad**: El productor Kafka utiliza `acks=all`, `enable.idempotence=true` y serialización JSON.
- **Orden por Orden**: Todos los eventos de una misma orden comparten la misma clave de partición (`ordenId`), garantizando que lleguen ordenados en la partición de Kafka.
- **Consumo Idempotente**: Los consumidores deben emplear `eventId` para dedup de mensajes (*at-least-once delivery*).

---

## 3. Esquema de Carga Útil (Payload Schema - Versión 1)

Todos los eventos cumplen con la estructura unificada `PagoEvento`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "PagoEvento",
  "type": "object",
  "required": [
    "eventId",
    "timestamp",
    "version",
    "eventType",
    "ordenId",
    "pagoId",
    "monto",
    "estado",
    "tarjetaHash",
    "ultimos4"
  ],
  "properties": {
    "eventId": {
      "type": "string",
      "format": "uuid",
      "description": "Identificador global único del evento (para deduplicación)."
    },
    "timestamp": {
      "type": "string",
      "format": "date-time",
      "description": "Fecha y hora en formato ISO-8601 UTC."
    },
    "version": {
      "type": "integer",
      "description": "Versión del esquema de eventos (actual: 1)."
    },
    "eventType": {
      "type": "string",
      "enum": [
        "PAGO_INTENTO_REGISTRADO",
        "PAGO_AUTORIZADO",
        "PAGO_RECHAZADO",
        "PAGO_FRAUDE_DETECTADO"
      ]
    },
    "ordenId": {
      "type": "string",
      "format": "uuid",
      "description": "Identificador de la orden asociada en ms-orders."
    },
    "pagoId": {
      "type": "string",
      "format": "uuid",
      "description": "Identificador del pago registrado en ms-pago."
    },
    "monto": {
      "type": "number",
      "description": "Monto de la transacción."
    },
    "estado": {
      "type": "string",
      "enum": ["INICIADO", "AUTORIZADO", "RECHAZADO", "BLOQUEADO_FRAUDE"]
    },
    "tarjetaHash": {
      "type": "string",
      "pattern": "^[a-f0-9]{64}$",
      "description": "Hash HMAC-SHA256 del PAN para correlación y scoring analítico."
    },
    "ultimos4": {
      "type": "string",
      "maxLength": 4,
      "description": "Últimos 4 dígitos del PAN (no confidencial)."
    },
    "motivo": {
      "type": "string",
      "description": "Razón o mensaje de la respuesta/bloqueo."
    }
  }
}
```

> [!IMPORTANT]
> **Privacidad y PCI-DSS**: El evento NUNCA incluye el número de tarjeta completo (PAN), fecha de expiración ni código de seguridad (CVV). El campo `tarjetaHash` es un hash unidireccional con sal secreta configurada únicamente en el servidor.

---

## 4. Ejemplos de Eventos

### 4.1. `pago.intento-registrado`
```json
{
  "eventId": "c8e76a6b-2856-4b89-a477-85b88c0378bc",
  "timestamp": "2026-11-08T22:30:00.123Z",
  "version": 1,
  "eventType": "PAGO_INTENTO_REGISTRADO",
  "ordenId": "7b3b4f62-3bf2-4da8-963b-6323497d39d1",
  "pagoId": "e331c19b-f111-4770-bc0b-a9b0ec031a28",
  "monto": 25990.00,
  "estado": "INICIADO",
  "tarjetaHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "ultimos4": "9012",
  "motivo": "Intento de pago iniciado"
}
```

### 4.2. `pago.autorizado`
```json
{
  "eventId": "a1b2c3d4-e5f6-4a1b-8c2d-3e4f5a6b7c8d",
  "timestamp": "2026-11-08T22:30:04.567Z",
  "version": 1,
  "eventType": "PAGO_AUTORIZADO",
  "ordenId": "7b3b4f62-3bf2-4da8-963b-6323497d39d1",
  "pagoId": "e331c19b-f111-4770-bc0b-a9b0ec031a28",
  "monto": 25990.00,
  "estado": "AUTORIZADO",
  "tarjetaHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "ultimos4": "9012",
  "motivo": "Transacción autorizada exitosamente"
}
```

### 4.3. `pago.rechazado`
```json
{
  "eventId": "f5e4d3c2-b1a0-4f8e-9d7c-6b5a4e3d2c1b",
  "timestamp": "2026-11-08T22:31:12.890Z",
  "version": 1,
  "eventType": "PAGO_RECHAZADO",
  "ordenId": "8f4a1234-5678-4abc-def0-123456789abc",
  "pagoId": "d442b28c-e222-4881-cd1c-b8c1fd142b39",
  "monto": 14990.00,
  "estado": "RECHAZADO",
  "tarjetaHash": "4a5b6c7d8e9f0123456789abcdef0123456789abcdef0123456789abcdef0123",
  "ultimos4": "0000",
  "motivo": "Rechazada: Fondos insuficientes (simulación Webpay)"
}
```

### 4.4. `pago.fraude-detectado`
```json
{
  "eventId": "99887766-5544-3322-1100-aabbccddeeff",
  "timestamp": "2026-11-08T22:32:05.101Z",
  "version": 1,
  "eventType": "PAGO_FRAUDE_DETECTADO",
  "ordenId": "a1a2a3a4-b1b2-c1c2-d1d2-e1e2e3e4e5e6",
  "pagoId": "77889900-1122-3344-5566-778899aabbcc",
  "monto": 39990.00,
  "estado": "BLOQUEADO_FRAUDE",
  "tarjetaHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "ultimos4": "9012",
  "motivo": "Bloqueado preventivamente por antifraude: 4 intentos en ventana de 10 segundos (máximo permitido: 3)"
}
```
