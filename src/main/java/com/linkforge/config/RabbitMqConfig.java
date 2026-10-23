package com.linkforge.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
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

    @Bean
    public TopicExchange clickExchange() {
        return new TopicExchange(CLICK_EXCHANGE, true, false);
    }

    @Bean
    public Queue clickQueue() {
        return new Queue(CLICK_QUEUE, true);
    }

    @Bean
    public Binding clickBinding(Queue clickQueue, TopicExchange clickExchange) {
        return BindingBuilder.bind(clickQueue).to(clickExchange).with(CLICK_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
