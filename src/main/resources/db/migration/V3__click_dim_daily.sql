-- Deepen phase A8: pre-aggregated click dimensions (device / referer_host).
-- Written only on the consumer path, inside the same idempotent transaction as
-- lf_click_daily, so a replayed eventId never double counts a bucket.

CREATE TABLE lf_click_dim_daily (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id   BIGINT       NOT NULL,
    link_id     BIGINT       NOT NULL,
    stat_date   DATE         NOT NULL,
    dim_type    VARCHAR(32)  NOT NULL COMMENT 'device / referer_host',
    dim_value   VARCHAR(128) NOT NULL,
    clicks      BIGINT       NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dim_link_date_value (link_id, stat_date, dim_type, dim_value),
    KEY idx_dim_tenant_date (tenant_id, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
