package com.linkforge.stats.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("lf_consumed_event")
public class ConsumedEvent {

    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;
    private LocalDateTime consumedAt;
}
