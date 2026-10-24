package com.linkforge.webhook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lf_webhook_delivery")
public class WebhookDelivery {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    /** Terminal state (this repo's dead-letter equivalent); manual replay is a SQL UPDATE back to PENDING. */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long endpointId;
    /** evt_ + ClickEvent.eventId; stable across retries and replays (D-Bw2-12). */
    private String eventId;
    private String eventType;
    private String payload;
    private String status;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private Integer lastStatusCode;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
