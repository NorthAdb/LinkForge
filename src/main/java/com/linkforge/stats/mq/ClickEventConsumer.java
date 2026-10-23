package com.linkforge.stats.mq;

import com.linkforge.config.RabbitMqConfig;
import com.linkforge.stats.service.ClickStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Real consumer. A clean return acks; an exception rejects without requeue and the
 * message lands in the dead-letter queue for inspection. Duplicate eventIds are acked
 * without counting again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickEventConsumer {

    private final ClickStatsService clickStatsService;

    @RabbitListener(queues = RabbitMqConfig.CLICK_QUEUE)
    public void onMessage(ClickEvent event) {
        boolean counted = clickStatsService.consume(event);
        log.debug("click event consumed code={} eventId={} counted={}",
                event.getCode(), event.getEventId(), counted);
    }
}
