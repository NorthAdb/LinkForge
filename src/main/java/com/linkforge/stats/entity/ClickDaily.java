package com.linkforge.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("lf_click_daily")
public class ClickDaily {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long linkId;
    private String code;
    private LocalDate statDate;
    private Long clicks;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
