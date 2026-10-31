-- C3 A/B weighted target split (D-C3-2): one short code may carry 0..N weighted
-- target variants in addition to the canonical lf_short_link.original_url.
-- Replacement is hard delete + insert (no logical delete column on purpose).
-- The redirect draws a variant only after every gate passed; gates (password,
-- phishing, UTM) stay on the link row (D-C3-9), so this table is never a gate input.

CREATE TABLE lf_link_variant (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id   BIGINT        NOT NULL,
    link_id     BIGINT        NOT NULL,
    variant_key VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    target_url  VARCHAR(2048) NOT NULL,
    weight      INT           NOT NULL COMMENT '1..10000, relative draw weight (D-C3-4)',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_link_variant (link_id, variant_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Variant keys are case-sensitive: A and a are two distinct experiments. Pinned to
-- utf8mb4_0900_bin (NO PAD, exact case) at CREATE time for the same reasons V4
-- chose it over PAD-SPACE utf8mb4_bin for lf_short_link.code; declaring it inline
-- avoids the rebuild V4 needed on a pre-existing column.
-- RULE (same as V4): joins go through link_id, never through a string column.

-- Clicks must remember which variant they drew: ClickLogReconciler rebuilds
-- ClickEvents from lf_click_log rows, so a replay after this column would silently
-- collapse every draw back into the un-bucketed default. Nullable: plain links
-- have no variant dimension at all.
ALTER TABLE lf_click_log ADD COLUMN variant VARCHAR(32) NULL;
