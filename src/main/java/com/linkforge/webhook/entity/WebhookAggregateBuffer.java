package com.linkforge.webhook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One (endpoint, window, link) bucket of AGGREGATE-mode clicks (D-C1-18). Rows are
 * short-lived: a flush (threshold, sweep, mode switch, or delete) drains them into
 * digest {@code lf_webhook_delivery} rows and deletes them in the same transaction.
 */
@Data
@TableName("lf_webhook_agg_buffer")
public class WebhookAggregateBuffer {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long endpointId;
    /** Epoch seconds of the window start (V9); DB-clock sweeps compare it to UNIX_TIMESTAMP(). */
    private Long windowStart;
    private Long linkId;
    private String code;
    private Integer clicks;
    private String sampleDevice;
    private String sampleRefererHost;
    private LocalDateTime sampleClickedAt;
    private Boolean late;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
