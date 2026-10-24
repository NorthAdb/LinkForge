-- B-wave2 B5: click webhooks on top of the A7 outbox chain.
-- endpoint.secret stores AES-GCM ciphertext ("v1:" + Base64(nonce||ct||tag)); the
-- master key comes from LINKFORGE_WEBHOOK_SECRET_KEY and is never persisted.
-- delivery.event_id is derived from ClickEvent.eventId and stays stable across
-- retries and replays; UNIQUE(endpoint_id, event_id) is the idempotency backstop.
CREATE TABLE lf_webhook_endpoint (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id   BIGINT        NOT NULL,
    url         VARCHAR(2048) NOT NULL,
    secret      VARCHAR(512)  NOT NULL COMMENT 'AES-GCM ciphertext, master key from env',
    enabled     TINYINT       NOT NULL DEFAULT 1,
    created_by  BIGINT        NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_webhook_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE lf_webhook_delivery (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id        BIGINT       NOT NULL,
    endpoint_id      BIGINT       NOT NULL,
    event_id         VARCHAR(80)  NOT NULL COMMENT 'evt_ + ClickEvent.eventId; stable across retries/replays',
    event_type       VARCHAR(32)  NOT NULL COMMENT 'link.clicked',
    payload          JSON         NOT NULL,
    status           VARCHAR(16)  NOT NULL COMMENT 'PENDING / SENT / FAILED',
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME     NULL,
    last_status_code INT          NULL,
    last_error       VARCHAR(512) NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at          DATETIME     NULL,
    UNIQUE KEY uk_webhook_endpoint_event (endpoint_id, event_id),
    KEY idx_webhook_pending (status, next_attempt_at, id),
    KEY idx_webhook_tenant (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
