package com.linkforge.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lf_click_log")
public class ClickLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long linkId;
    private String code;
    private String ip;
    private String userAgent;
    private String referer;
    private LocalDateTime clickedAt;
}
