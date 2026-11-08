-- Batch 3b (P1): malicious-host feed table. The URLhaus host feed is imported
-- here on a schedule (linkforge.malicious-feed, default off); create/update
-- verdicts read an in-memory snapshot of this table and stay purely local —
-- no per-request reputation call (D-Bw345-29). A host that disappears from
-- the feed is NOT auto-delisted: its row stays and last_seen simply stops
-- advancing, so delisting stays an operator decision.
CREATE TABLE lf_malicious_host (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    host       VARCHAR(255) NOT NULL COMMENT 'normalized host (IDN ASCII, lowercase, no trailing dot)',
    source     VARCHAR(32)  NOT NULL COMMENT 'feed source tag, e.g. urlhaus',
    first_seen DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_malicious_host (host)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
