package com.linkforge.webhook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lf_webhook_endpoint")
public class WebhookEndpoint {

    public static final String MODE_PER_CLICK = "PER_CLICK";
    public static final String MODE_AGGREGATE = "AGGREGATE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String url;
    /** AES-GCM ciphertext ("v1:" + Base64(nonce||ct||tag)); plaintext never persisted. */
    private String secret;
    private Boolean enabled;
    /** D-C1-18: PER_CLICK (default) or AGGREGATE; switching never rotates the secret. */
    private String deliveryMode;
    /** D-C1-19: AGGREGATE window length in seconds, valid 15..300. */
    private Integer aggregateWindowSeconds;
    /** D-C1-19: AGGREGATE events per window that seal it early, valid 1..100000. */
    private Integer aggregateMaxEvents;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
