CREATE TABLE IF NOT EXISTS pago (
    id UUID PRIMARY KEY,
    orden_id UUID NOT NULL,
    monto NUMERIC(12,2) NOT NULL,
    moneda VARCHAR(3) NOT NULL DEFAULT 'CLP',
    estado VARCHAR(30) NOT NULL,
    token_transaccion VARCHAR(255) UNIQUE,
    codigo_autorizacion VARCHAR(50),
    tarjeta_hash VARCHAR(64) NOT NULL,
    tarjeta_ultimos4 VARCHAR(4) NOT NULL,
    tarjeta_marca VARCHAR(30) NOT NULL,
    mensaje_respuesta TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pago_orden_id ON pago (orden_id);
CREATE INDEX IF NOT EXISTS idx_pago_token ON pago (token_transaccion);

CREATE TABLE IF NOT EXISTS alerta_fraude (
    id UUID PRIMARY KEY,
    orden_id UUID NOT NULL,
    pago_id UUID,
    tarjeta_hash VARCHAR(64) NOT NULL,
    tarjeta_ultimos4 VARCHAR(4) NOT NULL,
    intentos_detectados INTEGER NOT NULL,
    ventana_segundos INTEGER NOT NULL,
    motivo VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alerta_fraude_tarjeta ON alerta_fraude (tarjeta_hash);

CREATE TABLE IF NOT EXISTS outbox_evento (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(100) NOT NULL,
    partition_key VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    intentos INTEGER NOT NULL DEFAULT 0,
    ultimo_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outbox_estado ON outbox_evento (estado, created_at);
