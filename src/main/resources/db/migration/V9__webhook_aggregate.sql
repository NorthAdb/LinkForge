-- C1 wave C (D-C1-18..27): AGGREGATE webhook delivery mode. PER_CLICK stays the
-- default and byte-identical; AGGREGATE buffers clicks per (endpoint, window,
-- link) bucket and flushes a digest through the SAME delivery machinery. Mode
-- switches never rotate the secret (D-C1-25).
ALTER TABLE lf_webhook_endpoint ADD COLUMN delivery_mode VARCHAR(16) NOT NULL DEFAULT 'PER_CLICK' COMMENT 'PER_CLICK|AGGREGATE (D-C1-18)';
ALTER TABLE lf_webhook_endpoint ADD COLUMN aggregate_window_seconds INT NOT NULL DEFAULT 60 COMMENT '15..300 (D-C1-19)';
ALTER TABLE lf_webhook_endpoint ADD COLUMN aggregate_max_events INT NOT NULL DEFAULT 100 COMMENT '1..100000 (D-C1-19)';

-- One row per (endpoint, window, link) bucket; clicks counts the buffered events,
-- sample_* keeps the FIRST click of the bucket, late marks a click that arrived
-- after its own window had closed and joined the open one (D-C1-24).
CREATE TABLE lf_webhook_agg_buffer (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id           BIGINT       NOT NULL,
    endpoint_id         BIGINT       NOT NULL,
    window_start        BIGINT       NOT NULL COMMENT 'epoch seconds of the window start',
    link_id             BIGINT       NOT NULL,
    code                VARCHAR(16)  NOT NULL,
    clicks              INT          NOT NULL DEFAULT 1,
    sample_device       VARCHAR(32)  NULL,
    sample_referer_host VARCHAR(255) NULL,
    sample_clicked_at   DATETIME     NULL,
    late                TINYINT      NOT NULL DEFAULT 0,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agg_window_link (endpoint_id, window_start, link_id),
    KEY idx_agg_sweep (endpoint_id, window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
