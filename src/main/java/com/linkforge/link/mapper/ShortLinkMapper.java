package com.linkforge.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.link.entity.ShortLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLink> {

    /**
     * A9: startup seed for the process-local short-code Bloom filter. Native SQL
     * bypasses @TableLogic on purpose: logically-deleted rows still hold their
     * uk_code, so they must stay Bloom-positive and be rejected by the DB
     * (deleted=0 filter) instead of turning into pre-DB 404s.
     */
    @Select("SELECT code FROM lf_short_link")
    List<String> selectAllCodes();
}
