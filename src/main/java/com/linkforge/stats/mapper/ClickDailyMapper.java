package com.linkforge.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.stats.entity.ClickDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ClickDailyMapper extends BaseMapper<ClickDaily> {

    @Insert("INSERT INTO lf_click_daily(tenant_id, link_id, code, stat_date, clicks) "
            + "VALUES(#{tenantId}, #{linkId}, #{code}, #{statDate}, 1) "
            + "ON DUPLICATE KEY UPDATE clicks = clicks + 1")
    int incrementClick(@Param("tenantId") Long tenantId,
                       @Param("linkId") Long linkId,
                       @Param("code") String code,
                       @Param("statDate") LocalDate statDate);

    @Select("SELECT IFNULL(SUM(clicks), 0) FROM lf_click_daily WHERE link_id = #{linkId}")
    long sumClicks(@Param("linkId") Long linkId);

    @Select("SELECT * FROM lf_click_daily WHERE link_id = #{linkId} AND stat_date >= #{from} "
            + "AND stat_date <= #{to} ORDER BY stat_date")
    List<ClickDaily> selectRange(@Param("linkId") Long linkId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);
}
