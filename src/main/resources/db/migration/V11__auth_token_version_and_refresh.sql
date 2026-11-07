-- Batch 2a auth foundation: revocable access tokens + rotating refresh tokens.
-- token_version is the access-token revocation counter: JwtAuthFilter compares the
-- JWT "tv" claim against this column on every request, so bumping it kills every
-- outstanding access token on its next request. Tokens minted before V11 carry no
-- tv claim and read as 0, matching the column default — they live out their (now
-- 1h) natural life instead of dying at deploy time.
-- must_change_password is set by an admin password reset (batch 2b); while 1, the
-- JWT filter hard-blocks every authenticated request except change-password,
-- logout and refresh with 403 PASSWORD_CHANGE_REQUIRED.

ALTER TABLE lf_user ADD COLUMN token_version INT NOT NULL DEFAULT 0;
ALTER TABLE lf_user ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0;

-- Refresh tokens: only the SHA-256 hex of the raw token is stored (same discipline
-- as lf_api_key). One login session = one family_id; every refresh rotates the row
-- (revoked_at + revoke_cause=ROTATED, replaced_by_id points at the successor).
-- Presenting an already-revoked token outside the 10s rotation grace is treated as
-- theft: the whole family is revoked (REUSE_KILL) and token_version is bumped.
CREATE TABLE lf_refresh_token (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    family_id      VARCHAR(36)  NOT NULL COMMENT 'one login session = one rotation chain',
    token_hash     CHAR(64)     NOT NULL COMMENT 'SHA-256 hex of the raw token; raw value is never stored',
    expires_at     DATETIME(3)  NOT NULL,
    revoked_at     DATETIME(3)  NULL,
    revoke_cause   VARCHAR(16)  NULL COMMENT 'ROTATED / LOGOUT / REUSE_KILL / ADMIN / PASSWORD',
    replaced_by_id BIGINT       NULL COMMENT 'successor row id when revoke_cause=ROTATED',
    created_at     DATETIME(3)  NOT NULL,
    user_agent     VARCHAR(255) NULL,
    ip             VARCHAR(64)  NULL,
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY idx_refresh_user (user_id),
    KEY idx_refresh_family (family_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
