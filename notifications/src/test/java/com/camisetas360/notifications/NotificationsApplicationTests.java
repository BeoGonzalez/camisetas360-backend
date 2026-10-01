package com.camisetas360.notifications;

import com.camisetas360.notifications.controller.NotificationController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.health.mail.enabled=false",
        "MAIL_USERNAME=sender@example.test",
        "MAIL_PASSWORD=test-only",
        "MAIL_FROM=from@example.test"
})
class NotificationsApplicationTests {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    @MockitoBean
    private JavaMailSender mailSender;

    // IT-CFG-001
    @Test
    void contextLoads() {
        assertThat(context.getBean(NotificationController.class)).isNotNull();

    }
}
