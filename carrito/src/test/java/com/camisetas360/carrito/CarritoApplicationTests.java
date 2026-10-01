package com.camisetas360.carrito;

import com.camisetas360.carrito.controller.CartController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;


import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
})
class CarritoApplicationTests {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    // IT-CFG-001
    @Test
    void contextLoads() {
        assertThat(context.getBean(CartController.class)).isNotNull();

    }
}
