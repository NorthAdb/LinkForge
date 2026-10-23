package com.linkforge.stats.mq;

import com.linkforge.config.RabbitMqConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Stub consumer: acknowledges messages and logs. Aggregation / idempotent stats come later.
 */
@Slf4j
@Component
public class ClickEventConsumerStub {

    @RabbitListener(queues = RabbitMqConfig.CLICK_QUEUE)
    public void onMessage(ClickEvent event) {
        log.info("[stub] received click event code={} linkId={}", event.getCode(), event.getLinkId());
    }
}
