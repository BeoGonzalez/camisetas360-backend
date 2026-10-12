ALTER TABLE checkout_requests ADD COLUMN tenant_id UUID, ADD COLUMN object_id UUID;
ALTER TABLE checkout_requests ADD CONSTRAINT ck_checkout_requests_owner_pair
    CHECK ((tenant_id IS NULL) = (object_id IS NULL));
CREATE INDEX ix_checkout_requests_owner_created ON checkout_requests (tenant_id, object_id, created_at DESC);
-- Existing rows deliberately retain NULL owners. Never infer identities from contact email.
