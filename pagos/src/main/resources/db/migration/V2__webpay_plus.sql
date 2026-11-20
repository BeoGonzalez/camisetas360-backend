-- Webpay Plus real (Transbank): la tarjeta se ingresa en Webpay, asi que sus ultimos 4 digitos solo se
-- conocen al confirmar la transaccion. orden_compra es el buy_order enviado a Transbank (max 26
-- caracteres); identifica la transaccion cuando Webpay vuelve sin token (tiempo agotado).
ALTER TABLE pago ALTER COLUMN tarjeta_ultimos4 DROP NOT NULL;
ALTER TABLE pago ADD COLUMN orden_compra VARCHAR(26);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pago_orden_compra ON pago (orden_compra);
