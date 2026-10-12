package com.camisetas360.recommendation.messaging;

import com.camisetas360.recommendation.config.RabbitMQConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * AMQP Listener for order.created events.
 * Relies on {@link OrderEventInbox} abstraction (DIP) and publishes Micrometer metrics.
 */
@Component
public class OrderCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);

    public static final String METRIC_RECOMMENDATION_EVENTS = "recommendation.events";
    public static final String TAG_RESULT = "result";
    public static final String RESULT_ACCEPTED = "accepted";
    public static final String RESULT_DUPLICATE = "duplicate";
    public static final String RESULT_INVALID = "invalid";

    private final OrderEventInbox inbox;
    private final MeterRegistry metrics;

    public OrderCreatedListener(OrderEventInbox inbox, MeterRegistry metrics) {
        this.inbox = Objects.requireNonNull(inbox, "inbox cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    /**
     * Spring AUTO acknowledges only after the transactional inbox call commits.
     * Any {@link IllegalArgumentException} results in non-requeueable AMQP rejection for dead-lettering.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void receive(Message message) {
        try {
            byte[] body = message != null ? message.getBody() : null;
            boolean inserted = inbox.accept(body);
            metrics.counter(METRIC_RECOMMENDATION_EVENTS, TAG_RESULT,
                    inserted ? RESULT_ACCEPTED : RESULT_DUPLICATE).increment();

            boolean redelivered = message != null && message.getMessageProperties() != null
                    && Boolean.TRUE.equals(message.getMessageProperties().isRedelivered());

            log.info("Order event persisted; duplicate={}, redelivered={}", !inserted, redelivered);
        } catch (IllegalArgumentException invalid) {
            metrics.counter(METRIC_RECOMMENDATION_EVENTS, TAG_RESULT, RESULT_INVALID).increment();
            throw new AmqpRejectAndDontRequeueException("Invalid order.created event", invalid);
        }
    }
}
