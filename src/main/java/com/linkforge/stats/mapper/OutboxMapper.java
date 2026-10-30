package com.linkforge.stats.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.stats.entity.Outbox;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface OutboxMapper extends BaseMapper<Outbox> {

    @Select("SELECT * FROM lf_outbox WHERE status = 'PENDING' "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()) "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<Outbox> selectPendingBatch(@Param("limit") int limit);

    @Update("UPDATE lf_outbox SET status = 'SENT', sent_at = NOW() WHERE id = #{id}")
    int markSent(@Param("id") long id);

    @Update("UPDATE lf_outbox SET status = 'FAILED' WHERE id = #{id}")
    int markFailed(@Param("id") long id);

    @Update("UPDATE lf_outbox SET attempts = attempts + 1, next_attempt_at = "
            + "DATE_ADD(NOW(), INTERVAL #{backoffSeconds} SECOND) WHERE id = #{id}")
    int markRetry(@Param("id") long id, @Param("backoffSeconds") long backoffSeconds);

    /**
     * Retention cleanup. {@code sent_at} is written with {@code NOW()}, so the cutoff
     * is the DB clock too. Only terminal SENT rows; PENDING / FAILED stay.
     */
    @Delete("DELETE FROM lf_outbox WHERE status = 'SENT' AND sent_at IS NOT NULL "
            + "AND sent_at < DATE_SUB(NOW(), INTERVAL #{retentionSeconds} SECOND) LIMIT #{limit}")
    int deleteSentOlderThan(@Param("retentionSeconds") long retentionSeconds, @Param("limit") int limit);
}
