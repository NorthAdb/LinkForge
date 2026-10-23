package com.linkforge.stats.mq;

/**
 * Abstraction so RabbitMQ can be swapped later (e.g. RocketMQ) without touching redirect path.
 */
public interface ClickEventPublisher {
    void publish(ClickEvent event);
}
