package com.linkforge.auth;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row of a refresh-token rotation chain (V11). Only the SHA-256 hex of the
 * raw token is stored; the raw value exists solely in the response that issued
 * it. Rows are never deleted by the auth flows — revocation is a timestamp plus
 * a cause, which is what makes reuse detection possible.
 */
@Data
@TableName("lf_refresh_token")
public class RefreshToken {

    public static final String CAUSE_ROTATED = "ROTATED";
    public static final String CAUSE_LOGOUT = "LOGOUT";
    public static final String CAUSE_REUSE_KILL = "REUSE_KILL";
    public static final String CAUSE_ADMIN = "ADMIN";
    public static final String CAUSE_PASSWORD = "PASSWORD";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** One login session = one family; rotation stays inside it, logout kills it. */
    private String familyId;
    /** hex SHA-256 of the raw token; lookup happens on this column's unique index. */
    private String tokenHash;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    /** ROTATED / LOGOUT / REUSE_KILL / ADMIN / PASSWORD; null while the row is live. */
    private String revokeCause;
    /** Successor row id when this row was rotated away. */
    private Long replacedById;
    private LocalDateTime createdAt;
    private String userAgent;
    private String ip;
}
