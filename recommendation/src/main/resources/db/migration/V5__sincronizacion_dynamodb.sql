-- Cada interaccion se copia a DynamoDB (tabla UserInteractions de la guia "Recomendaciones
-- Kafka + TensorFlow"). PostgreSQL sigue siendo la fuente de verdad: estas columnas funcionan como
-- outbox, de modo que una caida de DynamoDB no pierde datos ni bloquea el consumo de eventos.
-- Las interacciones existentes quedan pendientes y se envian tambien (backfill).

ALTER TABLE interacciones
    ADD COLUMN dynamo_sincronizado_en TIMESTAMPTZ,
    ADD COLUMN dynamo_intentos        INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN dynamo_proximo_intento TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_interacciones_dynamo_pendientes
    ON interacciones (dynamo_proximo_intento, id)
    WHERE dynamo_sincronizado_en IS NULL;
