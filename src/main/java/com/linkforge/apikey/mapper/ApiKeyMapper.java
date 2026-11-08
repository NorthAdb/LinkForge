package com.linkforge.apikey.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.apikey.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {

    /** Best-effort last_used_at stamp, at most once per minute per key (SET NX gate in the
     * filter). Business column -> JVM clock passed in (B-wave2-fix D-Bw2fix-1). */
    @Update("UPDATE lf_api_key SET last_used_at = #{now} WHERE id = #{id}")
    void touchLastUsed(@Param("id") long id, @Param("now") LocalDateTime now);

    /** Audit M7: an admin password reset is compromise recovery — every still-live
     * key of the user (USER and TENANT scope alike) is revoked with the password,
     * permanently. Rows are kept (revoked_at is the tombstone the auth filter
     * uses to tell a known-but-dead key from an unknown one). */
    @Update("UPDATE lf_api_key SET revoked_at = #{now} WHERE user_id = #{userId} AND revoked_at IS NULL")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
