-- Gestion del catalogo desde el panel de administracion: equipo, tipo de camiseta e imagen.
ALTER TABLE products ADD COLUMN team VARCHAR(100);
ALTER TABLE products ADD COLUMN kit_type VARCHAR(20);
-- Version de la imagen (cache-busting en el front). NULL = el producto no tiene imagen.
ALTER TABLE products ADD COLUMN image_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE products ADD CONSTRAINT products_kit_type_chk
    CHECK (kit_type IS NULL OR kit_type IN ('LOCAL', 'VISITANTE', 'ALTERNATIVA'));

-- Las camisetas de la Premier League (V3) llevan el tipo en el SKU y el equipo en el nombre:
-- 'PL-ARS-LOC-2627' / 'Camiseta Arsenal Local 2026/27'. Las genericas de V2 quedan sin equipo ni tipo.
UPDATE products
SET kit_type = CASE split_part(sku, '-', 3)
                   WHEN 'LOC' THEN 'LOCAL'
                   WHEN 'VIS' THEN 'VISITANTE'
                   WHEN 'ALT' THEN 'ALTERNATIVA'
               END,
    team = substring(name FROM '^Camiseta (.+) (?:Local|Visitante|Alternativa) [0-9]{4}/[0-9]{2}$')
WHERE sku ~ '^PL-[A-Z]{3}-(LOC|VIS|ALT)-[0-9]{4}$';

-- Imagenes en tabla aparte: listar productos no carga los bytes.
CREATE TABLE product_images (
    product_id   BIGINT PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    content_type VARCHAR(30) NOT NULL,
    content      BYTEA       NOT NULL
);
