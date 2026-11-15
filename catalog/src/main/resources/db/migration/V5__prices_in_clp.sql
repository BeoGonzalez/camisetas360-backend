-- La tienda vende en pesos chilenos (CLP, sin decimales). Las semillas V2/V3 cargaron los precios
-- en una escala equivocada (94.99 en vez de 94.990): se llevan a pesos enteros.
-- Solo se tocan los valores con escala de "miles" (< 1000); un precio ya en pesos no cambia.
UPDATE products
SET price = ROUND(price * 1000)
WHERE price < 1000;
