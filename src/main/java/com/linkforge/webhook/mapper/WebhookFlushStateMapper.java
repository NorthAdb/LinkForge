package com.linkforge.webhook.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Audit fix M3: per-endpoint digest drain sequence (V15). All three statements
 * run inside the drain transaction: the caller ensures the row, reads the
 * sequence with a locking read, and persists the incremented value before
 * inserting any delivery — a rollback therefore leaves the sequence untouched
 * and a retry allocates the same sequence (and event ids) again.
 */
@Mapper
public interface WebhookFlushStateMapper {

    /** Creates the endpoint's state row when missing; a no-op touch otherwise
     * (which also takes the row's write lock briefly inside the caller's tx). */
    @Insert("INSERT INTO lf_webhook_flush_state (endpoint_id, flush_seq) VALUES (#{endpointId}, 0) "
            + "ON DUPLICATE KEY UPDATE flush_seq = flush_seq")
    int ensureRow(@Param("endpointId") Long endpointId);

    /** Current sequence under a locking read — serializes concurrent drains of
     * the same endpoint behind the state row. */
    @Select("SELECT flush_seq FROM lf_webhook_flush_state WHERE endpoint_id = #{endpointId} FOR UPDATE")
    Long selectSeqForUpdate(@Param("endpointId") Long endpointId);

    @Update("UPDATE lf_webhook_flush_state SET flush_seq = #{flushSeq} WHERE endpoint_id = #{endpointId}")
    int updateSeq(@Param("endpointId") Long endpointId, @Param("flushSeq") long flushSeq);
}
