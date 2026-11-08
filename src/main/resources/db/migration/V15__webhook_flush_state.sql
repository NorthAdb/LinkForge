-- Audit fix M3: the AGGREGATE digest drain allocates a persistent, per-endpoint
-- sequence so every drain of a window gets its own event ids
-- (digest-{endpointId}-{windowStart}-{flushSeq}). The old scheme reused the
-- bare window id for every batch, so a late bucket in an already-flushed
-- window collided on uk_webhook_endpoint_event and was deleted unsent.
-- One row per endpoint; the row is locked and the sequence advanced inside
-- the drain transaction, so a rolled-back drain consumes no sequence and a
-- retry reproduces the very same event ids.
CREATE TABLE lf_webhook_flush_state (
    endpoint_id BIGINT PRIMARY KEY,
    flush_seq   BIGINT NOT NULL DEFAULT 0 COMMENT 'last allocated digest drain sequence for the endpoint'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
