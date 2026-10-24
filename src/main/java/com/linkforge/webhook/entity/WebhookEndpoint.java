package com.linkforge.webhook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lf_webhook_endpoint")
public class WebhookEndpoint {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String url;
    /** AES-GCM ciphertext ("v1:" + Base64(nonce||ct||tag)); plaintext never persisted. */
    private String secret;
    private Boolean enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
