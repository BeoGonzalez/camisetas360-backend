package com.camisetas360.notifications.listener;

import com.camisetas360.notifications.config.RabbitMQConfig;
import com.camisetas360.notifications.event.OrderCreatedEvent;
import com.camisetas360.notifications.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderCreatedListener {

    private static final Logger log =
            LoggerFactory.getLogger(OrderCreatedListener.class);

    private final EmailService emailService;

    public OrderCreatedListener(
            EmailService emailService
    ) {
        this.emailService = emailService;
    }

    @RabbitListener(
            queues = RabbitMQConfig.NOTIFICATIONS_ORDER_CREATED_QUEUE
    )
    public void handleOrderCreated(
            OrderCreatedEvent event
    ) {

        log.info(
                "Evento order.created recibido. orderId={}, userEmail={}",
                event.orderId(),
                event.userEmail()
        );

        String subject =
                "Orden creada #" + event.orderId();

        String body = """
                Hola,

                Tu orden fue creada correctamente.

                Número de orden: %s
                Total: $%.2f
                Estado: CREATED

                Gracias por comprar en Camisetas360.
                """.formatted(
                event.orderId(),
                event.totalAmount()
        );

        emailService.sendEmail(
                event.userEmail(),
                subject,
                body
        );

        log.info(
                "Correo enviado para orderId={} a {}",
                event.orderId(),
                event.userEmail()
        );
    }
}