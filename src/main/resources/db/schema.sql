-- LinkForge MVP schema (MySQL 8)
CREATE DATABASE IF NOT EXISTS linkforge DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE linkforge;

CREATE TABLE IF NOT EXISTS lf_tenant (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    name          VARCHAR(64)  NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=active 0=disabled',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_tenant_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lf_user (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status        TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_tenant_username (tenant_id, username),
    KEY idx_user_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lf_short_link (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    code          VARCHAR(16)  NOT NULL,
    original_url  VARCHAR(2048) NOT NULL,
    title         VARCHAR(128) NULL,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=active 0=disabled',
    expire_at     DATETIME     NULL,
    created_by    BIGINT       NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_code (code),
    KEY idx_link_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lf_click_log (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    link_id       BIGINT       NOT NULL,
    code          VARCHAR(16)  NOT NULL,
    ip            VARCHAR(64)  NULL,
    user_agent    VARCHAR(512) NULL,
    referer       VARCHAR(512) NULL,
    clicked_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_click_link_time (link_id, clicked_at),
    KEY idx_click_tenant_time (tenant_id, clicked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lf_click_daily (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    link_id       BIGINT       NOT NULL,
    code          VARCHAR(16)  NOT NULL,
    stat_date     DATE         NOT NULL,
    clicks        BIGINT       NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_daily_link_date (link_id, stat_date),
    KEY idx_daily_tenant_date (tenant_id, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lf_consumed_event (
    event_id      VARCHAR(64) PRIMARY KEY,
    consumed_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
