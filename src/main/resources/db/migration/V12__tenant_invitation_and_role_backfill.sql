-- Batch 2b team collaboration: bearer invitations + role backfill.
-- An invitation is a single-use, expiring bearer credential: only the SHA-256 hex
-- of the raw token is stored (same discipline as lf_refresh_token / lf_api_key);
-- the raw token appears exactly once, in the creation response, and the creator
-- hands the link to the invitee out of band (there is no mail infrastructure).
-- accepted_by/accepted_at are written by one conditional UPDATE (single-use under
-- concurrency); revoked_at is the creator-side kill switch.

CREATE TABLE lf_tenant_invitation (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id    BIGINT       NOT NULL,
    token_hash   CHAR(64)     NOT NULL COMMENT 'SHA-256 hex of the raw invitation token; raw value is never stored',
    invited_role VARCHAR(16)  NOT NULL DEFAULT 'MEMBER' COMMENT 'ADMIN / MEMBER, granted to the account on accept',
    note_name    VARCHAR(64)  NULL COMMENT 'creator-side label for an outstanding invitation',
    created_by   BIGINT       NOT NULL,
    expires_at   DATETIME(3)  NOT NULL,
    accepted_by  BIGINT       NULL,
    accepted_at  DATETIME(3)  NULL,
    revoked_at   DATETIME(3)  NULL,
    created_at   DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_invitation_token_hash (token_hash),
    KEY idx_invitation_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Role formalization: tenant_role becomes a written fact (ADMIN / MEMBER), not a
-- convention where NULL means MEMBER. The column has carried a 'MEMBER' default
-- since V8, so on most databases this UPDATE touches nothing; it stays as the
-- batch's explicit guarantee, and read sites keep tolerating NULL regardless.
UPDATE lf_user SET tenant_role = 'MEMBER' WHERE tenant_role IS NULL;

-- Member removal deliberately adds NO column here: removal reuses the existing
-- MyBatis-Plus logical-delete flag (lf_user.deleted, @TableLogic on UserAccount).
-- Every credential recheck (JWT filter, API-key owner check, refresh lookup)
-- loads the user through the mapper, which excludes logically deleted rows, so a
-- removed member's access / refresh / API keys all die on the next request while
-- the row — and the tenant assets it created (links, webhooks) — stay in place.
