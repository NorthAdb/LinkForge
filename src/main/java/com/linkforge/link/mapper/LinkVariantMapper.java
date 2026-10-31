package com.linkforge.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.link.entity.LinkVariant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LinkVariantMapper extends BaseMapper<LinkVariant> {

    @Select("SELECT * FROM lf_link_variant WHERE link_id = #{linkId} ORDER BY id ASC")
    List<LinkVariant> selectByLinkId(@Param("linkId") Long linkId);
}
