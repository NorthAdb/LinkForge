package com.linkforge.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.stats.entity.ClickLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ClickLogMapper extends BaseMapper<ClickLog> {

    @Select("SELECT l.* FROM lf_click_log l "
            + "LEFT JOIN lf_consumed_event c ON c.event_id = CONCAT('log-', l.id) "
            + "WHERE c.event_id IS NULL ORDER BY l.id LIMIT #{limit}")
    List<ClickLog> selectUnconsumed(@Param("limit") int limit);
}
