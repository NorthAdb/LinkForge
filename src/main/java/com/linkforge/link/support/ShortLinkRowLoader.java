package com.linkforge.link.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.link.entity.ShortLink;
import com.linkforge.link.mapper.ShortLinkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The redirect hot path's code -> row read, extracted into its own bean (not a
 * MyBatis proxy) so it can sit behind the singleflight loader and be spied in
 * tests. Management APIs keep their own direct mapper reads.
 */
@Component
@RequiredArgsConstructor
public class ShortLinkRowLoader {

    private final ShortLinkMapper shortLinkMapper;

    public ShortLink load(String code) {
        return shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getCode, code));
    }
}
