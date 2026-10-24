package com.linkforge.stats.mq;

import com.linkforge.config.RabbitMqConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Confirm-aware publish (A7 audit S1): returns only after the broker acks the
 * message (correlated confirms + mandatory returns, both on in application.yml).
 * A nack, an unroutable message or a confirm timeout throws, so the outbox relay
 * keeps the row PENDING and retries instead of silently losing the click.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitClickEventPublisher implements ClickEventPublisher {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    /** correlationIds of messages the broker returned as unroutable. */
    private final Set<String> returnedIds = ConcurrentHashMap.newKeySet();

    private final RabbitTemplate rabbitTemplate;

    @PostConstruct
    void trackReturns() {
        rabbitTemplate.setReturnsCallback(returned -> {
            String id = returned.getMessage().getMessageProperties().getCorrelationId();
            if (id != null) {
                returnedIds.add(id);
            }
        });
    }

    @Override
    public void publish(ClickEvent event) {
        CorrelationData correlation = new CorrelationData(event.getEventId());
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.CLICK_EXCHANGE,
                RabbitMqConfig.CLICK_ROUTING_KEY,
                event,
                correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("broker nacked event " + event.getEventId());
            }
            if (returnedIds.remove(correlation.getId())) {
                throw new IllegalStateException("unroutable event " + event.getEventId());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("confirm wait interrupted for " + event.getEventId(), ex);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("publisher confirm failed for " + event.getEventId(), ex);
        }
        log.debug("published click event code={} (confirmed)", event.getCode());
    }
}
