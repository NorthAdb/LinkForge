package com.linkforge.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One outstanding (or spent) tenant invitation (V12). Only the SHA-256 hex of
 * the raw token is stored; the raw value exists solely in the creation
 * response. A row is acceptable exactly while {@code acceptedAt == null &&
 * revokedAt == null && expiresAt > now} — the accept path re-checks all three
 * in one conditional UPDATE so an invitation can be consumed only once, even
 * under concurrent accepts.
 */
@Data
@TableName("lf_tenant_invitation")
public class TenantInvitation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** hex SHA-256 of the raw token; lookup happens on this column's unique index. */
    private String tokenHash;
    /** ADMIN / MEMBER — the role the accepted account is created with. */
    private String invitedRole;
    /** Creator-side label ("new ops hire"); never shown to the invitee. */
    private String noteName;
    private Long createdBy;
    private LocalDateTime expiresAt;
    private Long acceptedBy;
    private LocalDateTime acceptedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
}
