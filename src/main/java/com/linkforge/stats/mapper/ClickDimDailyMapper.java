package com.linkforge.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.stats.dto.DimBreakdown;
import com.linkforge.stats.entity.ClickDimDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ClickDimDailyMapper extends BaseMapper<ClickDimDaily> {

    /**
     * Counter upsert. Duplicate delivery never reaches here twice for one eventId
     * (the consumed-event dedup gates this in the same transaction), so the +1 is
     * safe; ON DUPLICATE KEY UPDATE is the right tool for a counter, unlike the
     * duplicate-DETECTION case rejected in decision #11.
     */
    @Insert("INSERT INTO lf_click_dim_daily (tenant_id, link_id, stat_date, dim_type, dim_value, clicks) "
            + "VALUES (#{tenantId}, #{linkId}, #{statDate}, #{dimType}, #{dimValue}, 1) "
            + "ON DUPLICATE KEY UPDATE clicks = clicks + 1")
    int incrementClick(@Param("tenantId") long tenantId,
                       @Param("linkId") long linkId,
                       @Param("statDate") LocalDate statDate,
                       @Param("dimType") String dimType,
                       @Param("dimValue") String dimValue);

    @Select("SELECT dim_value AS dimValue, SUM(clicks) AS clicks FROM lf_click_dim_daily "
            + "WHERE link_id = #{linkId} AND dim_type = #{dimType} "
            + "AND stat_date >= #{from} AND stat_date <= #{to} "
            + "GROUP BY dim_value ORDER BY clicks DESC, dim_value ASC")
    List<DimBreakdown> selectBreakdown(@Param("linkId") long linkId,
                                       @Param("dimType") String dimType,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);
}
