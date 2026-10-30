package com.linkforge.webhook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.webhook.entity.WebhookDelivery;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Clock rule (B-wave2-fix D-Bw2fix-1): {@code next_attempt_at} is a scheduling
 * column — written and compared with the DB clock (DATE_ADD(NOW(), ...) vs NOW(),
 * same as the A7 outbox mapper). {@code sent_at} / {@code created_at} are business
 * columns — written from the JVM clock by the caller.
 */
@Mapper
public interface WebhookDeliveryMapper extends BaseMapper<WebhookDelivery> {

    @Select("SELECT * FROM lf_webhook_delivery WHERE status = 'PENDING' "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()) ORDER BY id LIMIT #{limit}")
    List<WebhookDelivery> selectPendingBatch(@Param("limit") int limit);

    @Update("UPDATE lf_webhook_delivery SET status = 'SENT', attempts = attempts + 1, "
            + "last_status_code = #{code}, sent_at = #{now}, last_error = NULL WHERE id = #{id} AND status = 'PENDING'")
    int markSent(@Param("id") long id, @Param("code") int code, @Param("now") LocalDateTime now);

    @Update("UPDATE lf_webhook_delivery SET attempts = attempts + 1, "
            + "next_attempt_at = DATE_ADD(NOW(), INTERVAL #{backoffSeconds} SECOND), "
            + "last_status_code = #{code}, last_error = #{error} WHERE id = #{id} AND status = 'PENDING'")
    int markRetry(@Param("id") long id, @Param("backoffSeconds") long backoffSeconds,
                  @Param("code") Integer code, @Param("error") String error);

    @Update("UPDATE lf_webhook_delivery SET status = 'FAILED', attempts = attempts + 1, "
            + "last_status_code = #{code}, last_error = #{error} WHERE id = #{id} AND status = 'PENDING'")
    int markFailed(@Param("id") long id, @Param("code") Integer code, @Param("error") String error);

    /**
     * Retention cleanup. {@code sent_at} is a business column written from the JVM
     * clock, so the caller passes a JVM cutoff — comparing it to {@code NOW()} would
     * reintroduce the JVM/DB timezone skew this mapper's clock rule exists to avoid.
     * Only terminal SENT rows; PENDING / FAILED stay.
     */
    @Delete("DELETE FROM lf_webhook_delivery WHERE status = 'SENT' AND sent_at IS NOT NULL "
            + "AND sent_at < #{cutoff} LIMIT #{limit}")
    int deleteSentOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
