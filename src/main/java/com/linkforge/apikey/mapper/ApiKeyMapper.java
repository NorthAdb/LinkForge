package com.linkforge.apikey.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.apikey.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {

    /** Best-effort last_used_at stamp, at most once per minute per key (SET NX gate in the filter). */
    @Update("UPDATE lf_api_key SET last_used_at = NOW() WHERE id = #{id}")
    void touch(@Param("id") long id);
}
