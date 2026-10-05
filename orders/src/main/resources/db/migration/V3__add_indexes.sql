CREATE INDEX idx_orders_user_email_created_at ON orders (user_email, created_at DESC);
CREATE INDEX idx_order_items_order_id ON order_items (order_id);
