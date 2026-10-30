package com.linkforge.webhook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.webhook.entity.WebhookAggregateBuffer;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AGGREGATE buffer access (D-C1-18..25). Clock rule: {@code window_start} is a
 * scheduling column — the sweep compares it to {@code UNIX_TIMESTAMP()} on the DB
 * clock, exactly like {@code next_attempt_at} in WebhookDeliveryMapper. The sample
 * columns and {@code created_at} are business data written by the JVM-clock caller.
 */
@Mapper
public interface WebhookAggregateBufferMapper extends BaseMapper<WebhookAggregateBuffer> {

    /**
     * Idempotent bucket increment (D-C1-18): a repeat only adds to clicks and ors
     * the late flag; sample_* keep the FIRST click of the bucket (untouched on dup).
     */
    @Insert("INSERT INTO lf_webhook_agg_buffer(tenant_id, endpoint_id, window_start, link_id, code, "
            + "clicks, sample_device, sample_referer_host, sample_clicked_at, late) "
            + "VALUES(#{tenantId}, #{endpointId}, #{windowStart}, #{linkId}, #{code}, "
            + "1, #{sampleDevice}, #{sampleRefererHost}, #{sampleClickedAt}, #{late}) "
            + "ON DUPLICATE KEY UPDATE clicks = clicks + 1, late = late | VALUES(late)")
    int upsertClick(@Param("tenantId") Long tenantId,
                    @Param("endpointId") Long endpointId,
                    @Param("windowStart") long windowStart,
                    @Param("linkId") Long linkId,
                    @Param("code") String code,
                    @Param("late") boolean late,
                    @Param("sampleDevice") String sampleDevice,
                    @Param("sampleRefererHost") String sampleRefererHost,
                    @Param("sampleClickedAt") LocalDateTime sampleClickedAt);

    @Select("SELECT IFNULL(SUM(clicks), 0) FROM lf_webhook_agg_buffer "
            + "WHERE endpoint_id = #{endpointId} AND window_start = #{windowStart}")
    long sumClicks(@Param("endpointId") Long endpointId, @Param("windowStart") long windowStart);

    /** The window's buckets in stable link_id order — digest parts are deterministic slices. */
    @Select("SELECT * FROM lf_webhook_agg_buffer WHERE endpoint_id = #{endpointId} "
            + "AND window_start = #{windowStart} ORDER BY link_id")
    List<WebhookAggregateBuffer> selectWindow(@Param("endpointId") Long endpointId,
                                              @Param("windowStart") long windowStart);

    /** Every window the endpoint still buffers, oldest first (mode-switch flush-all). */
    @Select("SELECT DISTINCT window_start FROM lf_webhook_agg_buffer "
            + "WHERE endpoint_id = #{endpointId} ORDER BY window_start")
    List<Long> selectWindowStarts(@Param("endpointId") Long endpointId);

    /**
     * Sweep input (D-C1-21): windows of live AGGREGATE endpoints whose last second is
     * behind the DB clock. endpoint_id/window_start are the GROUP BY columns; other
     * entity fields stay null.
     */
    @Select("SELECT b.endpoint_id, b.window_start FROM lf_webhook_agg_buffer b "
            + "JOIN lf_webhook_endpoint e ON e.id = b.endpoint_id "
            + "WHERE e.delivery_mode = 'AGGREGATE' AND e.enabled = 1 "
            + "AND b.window_start <= UNIX_TIMESTAMP() - e.aggregate_window_seconds "
            + "GROUP BY b.endpoint_id, b.window_start ORDER BY b.endpoint_id, b.window_start")
    List<WebhookAggregateBuffer> selectExpiredWindows();

    @Delete("DELETE FROM lf_webhook_agg_buffer WHERE endpoint_id = #{endpointId} AND window_start = #{windowStart}")
    int deleteWindow(@Param("endpointId") Long endpointId, @Param("windowStart") long windowStart);
}
