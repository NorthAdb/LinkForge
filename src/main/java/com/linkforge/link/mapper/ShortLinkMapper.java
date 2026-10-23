package com.linkforge.link.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.link.entity.ShortLink;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShortLinkMapper extends BaseMapper<ShortLink> {
}
