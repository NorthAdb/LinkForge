-- Deepen phase A7: transactional outbox for click events.
-- The redirect path writes PENDING rows in the same transaction as the (optional)
-- sync detail; ClickOutboxRelay delivers them to RabbitMQ and marks them SENT.

CREATE TABLE lf_outbox (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id        VARCHAR(64)  NOT NULL,
    payload         JSON         NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING / SENT',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at         DATETIME     NULL,
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_pending (status, next_attempt_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- NULL (default): hot-path sync row, counted under the implicit "log-{id}" eventId.
-- Non-NULL: detail backfilled by the consumer for a UUID outbox event; already
-- counted, so ClickLogReconciler must skip it.
ALTER TABLE lf_click_log ADD COLUMN event_id VARCHAR(64) NULL;
