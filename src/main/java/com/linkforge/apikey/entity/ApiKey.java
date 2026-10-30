package com.linkforge.apikey.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lf_api_key")
public class ApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** Creator; key-authenticated requests act as this user (D-Bw2-3). */
    private Long userId;
    /** USER|TENANT; TENANT sees every link in its tenant (D-C1-12). */
    private String scope;
    private String name;
    /** Display-only prefix (lfk_xxxxxxxx); the full key is never stored. */
    private String keyPrefix;
    /** hex SHA-256 of the full plaintext key; lookup happens on this column's unique index. */
    private String keyHash;
    private Integer quotaPerMinute;
    private Integer quotaPerDay;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
}
