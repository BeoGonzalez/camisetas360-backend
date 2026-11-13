-- Carrito persistente por usuario (identidad inmutable de Entra: tenant + object id).
CREATE TABLE cart_items (
    tenant_id UUID NOT NULL,
    object_id UUID NOT NULL,
    sku VARCHAR(64) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    category VARCHAR(255),
    unit_price DOUBLE PRECISION NOT NULL CHECK (unit_price > 0),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, object_id, sku)
);

-- Envio elegido para el carrito. No se guarda la direccion exacta (dato personal).
CREATE TABLE cart_shipping (
    tenant_id UUID NOT NULL,
    object_id UUID NOT NULL,
    method VARCHAR(20) NOT NULL CHECK (method IN ('STANDARD', 'EXPRESS', 'RETIRO_TIENDA')),
    region VARCHAR(100) NOT NULL,
    commune VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, object_id)
);
