package com.linkforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String CLICK_EXCHANGE = "linkforge.click.exchange";
    public static final String CLICK_QUEUE = "linkforge.click.queue";
    public static final String CLICK_ROUTING_KEY = "click.recorded";
    public static final String CLICK_DLX = "linkforge.click.dlx";
    public static final String CLICK_DLQ = "linkforge.click.dlq";
    public static final String CLICK_DLQ_KEY = "click.dead";

    @Bean
    public TopicExchange clickExchange() {
        return new TopicExchange(CLICK_EXCHANGE, true, false);
    }

    @Bean
    public Queue clickQueue() {
        return QueueBuilder.durable(CLICK_QUEUE)
                .deadLetterExchange(CLICK_DLX)
                .deadLetterRoutingKey(CLICK_DLQ_KEY)
                .build();
    }

    @Bean
    public Binding clickBinding(Queue clickQueue, TopicExchange clickExchange) {
        return BindingBuilder.bind(clickQueue).to(clickExchange).with(CLICK_ROUTING_KEY);
    }

    @Bean
    public TopicExchange clickDlx() {
        return new TopicExchange(CLICK_DLX, true, false);
    }

    @Bean
    public Queue clickDlq() {
        return new Queue(CLICK_DLQ, true);
    }

    @Bean
    public Binding clickDlqBinding(Queue clickDlq, TopicExchange clickDlx) {
        return BindingBuilder.bind(clickDlq).to(clickDlx).with(CLICK_DLQ_KEY);
    }

    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        // Spring's mapper has JavaTimeModule; the converter's default one cannot
        // round-trip LocalDateTime and dead-letters every ClickEvent.
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
