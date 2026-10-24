-- B-wave2 B4: API keys for external integrators.
-- Only the SHA-256 hex of the full plaintext key is stored (high-entropy random
-- string, so a fast hash is appropriate); the plaintext is returned exactly once
-- in the create response and never persisted or logged. key_prefix is display-only.
-- Requests authenticated by a key act as the creating user (D-Bw2-3).
CREATE TABLE lf_api_key (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id        BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL COMMENT 'creator; requests act as this user',
    name             VARCHAR(64)  NOT NULL,
    key_prefix       VARCHAR(16)  NOT NULL COMMENT 'lfk_xxxxxxxx, display only',
    key_hash         CHAR(64)     NOT NULL COMMENT 'hex SHA-256 of the full plaintext key',
    quota_per_minute INT          NULL     COMMENT 'NULL = linkforge.api-key default',
    quota_per_day    INT          NULL     COMMENT 'NULL = linkforge.api-key default',
    last_used_at     DATETIME     NULL,
    revoked_at       DATETIME     NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_api_key_hash (key_hash),
    KEY idx_api_key_user (user_id),
    KEY idx_api_key_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
