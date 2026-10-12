CREATE TABLE recommendation_order_inbox (
    event_id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ
);
CREATE INDEX idx_recommendation_inbox_pending
    ON recommendation_order_inbox (received_at) WHERE processed_at IS NULL;
