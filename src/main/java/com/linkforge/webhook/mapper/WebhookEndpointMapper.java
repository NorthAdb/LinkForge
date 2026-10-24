package com.linkforge.webhook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkforge.webhook.entity.WebhookEndpoint;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WebhookEndpointMapper extends BaseMapper<WebhookEndpoint> {
}
