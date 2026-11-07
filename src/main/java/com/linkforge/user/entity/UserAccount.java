package com.linkforge.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lf_user")
public class UserAccount {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String username;
    private String passwordHash;
    private Integer status;
    private String tenantRole;
    /** Access-token revocation counter (V11): the JWT "tv" claim must match this. */
    private Integer tokenVersion;
    /** 1 = an admin reset this password; the JWT filter forces a change (V11). */
    private Integer mustChangePassword;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
