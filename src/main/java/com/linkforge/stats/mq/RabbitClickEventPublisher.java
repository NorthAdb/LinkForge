package com.linkforge.stats.mq;

import com.linkforge.config.RabbitMqConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitClickEventPublisher implements ClickEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(ClickEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.CLICK_EXCHANGE,
                RabbitMqConfig.CLICK_ROUTING_KEY,
                event);
        log.debug("published click event code={}", event.getCode());
    }
}
