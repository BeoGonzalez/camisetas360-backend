package com.camisetas360.orders;

import com.camisetas360.orders.controller.OrderController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;


import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "spring.datasource.url=jdbc:h2:mem:orders-smoke;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrdersApplicationTests {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    // IT-CFG-001
    @Test
    void contextLoads() {
        assertThat(context.getBean(OrderController.class)).isNotNull();

    }
}
