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
}
