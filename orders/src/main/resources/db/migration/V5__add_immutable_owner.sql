ALTER TABLE orders ADD COLUMN tenant_id UUID, ADD COLUMN object_id UUID;
ALTER TABLE orders ADD CONSTRAINT ck_orders_owner_pair
    CHECK ((tenant_id IS NULL) = (object_id IS NULL));
CREATE INDEX ix_orders_owner_created ON orders (tenant_id, object_id, created_at DESC);
-- Existing rows deliberately retain NULL owners. Never infer identities from contact email.
