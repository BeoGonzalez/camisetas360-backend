package com.camisetas360.notifications.integration;

import com.camisetas360.notifications.repository.EmailDeliveryRepository;
import com.camisetas360.notifications.service.EmailService;
import com.camisetas360.notifications.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.health.mail.enabled=false", "MAIL_USERNAME=test", "MAIL_PASSWORD=test",
        "MAIL_FROM=orders@camisetas360.test"
})
class EmailDeliveryPersistenceIT extends PostgresTestSupport {
    @Autowired EmailService service;
    @Autowired EmailDeliveryRepository deliveries;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender sender;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void cleanDeliveries() { deliveries.deleteAll(); }

    @Test void successfulSmtpSendCommitsTheExactDeliveryLog() {
        Instant before = Instant.now();
        service.sendEmail("buyer@example.test", "Orden creada #42", "Gracias, 東京");
        var rows = deliveries.findByRecipientOrderBySentAtDesc("buyer@example.test");
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.getSender()).isEqualTo("orders@camisetas360.test");
        assertThat(row.getRecipient()).isEqualTo("buyer@example.test");
        assertThat(row.getSubject()).isEqualTo("Orden creada #42");
        assertThat(row.getBody()).isEqualTo("Gracias, 東京");
        assertThat(row.getSentAt()).isBetween(before.minusNanos(1000), Instant.now());
        assertThat(jdbc.queryForObject("SELECT body FROM email_deliveries", String.class)).isEqualTo("Gracias, 東京");
        assertThat(deliveries.findByRecipientOrderBySentAtDesc("other@example.test")).isEmpty();
    }

    @Test void smtpFailureRollsBackTheFlushedDeliveryInsteadOfRecordingSuccess() {
        doThrow(new MailSendException("SMTP unavailable")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> service.sendEmail("buyer@example.test", "Order", "Body")).isInstanceOf(MailSendException.class);
        assertThat(deliveries.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_deliveries", Integer.class)).isZero();
    }

    @Test void invalidPersistencePreventsSmtpAndDoesNotLeaveAFalseDelivery() {
        assertThatThrownBy(() -> service.sendEmail("buyer@example.test", null, "Body"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verifyNoInteractions(sender);
        assertThat(deliveries.count()).isZero();
    }

    @Test void flywayCreatesRequiredDeliveryFieldsAndTheHistoryIndex() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name='email_deliveries' AND column_name='sent_at'", String.class)).isEqualTo("timestamp with time zone");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO email_deliveries (sender,recipient,subject,body,sent_at) VALUES ('from','to',NULL,'body',now())"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='ix_email_deliveries_recipient_sent_at'", Integer.class)).isEqualTo(1);
    }
}
