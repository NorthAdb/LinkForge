package com.linkforge.stats.mq;

/**
 * Abstraction over the concrete broker client so the implementation can be swapped
 * without touching the relay. Implementations must only return after the broker
 * accepted the message (A7 audit S1).
 */
public interface ClickEventPublisher {
    void publish(ClickEvent event);
}
