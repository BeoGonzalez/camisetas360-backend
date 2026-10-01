package com.camisetas360.notifications.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;
    private EmailService service;

    @BeforeEach
    void setUp() {
        service = new EmailService(mailSender, "shop@example.test");
    }

    // UT-NOT-001
    @Test
    void sendEmail_shouldSendCompleteMessage_whenArgumentsAreValid() {
        service.sendEmail("buyer@example.test", "Orden creada #42", "Gracias por tu compra.");

        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        var message = captor.getValue();
        assertThat(message.getFrom()).isEqualTo("shop@example.test");
        assertThat(message.getTo()).containsExactly("buyer@example.test");
        assertThat(message.getSubject()).isEqualTo("Orden creada #42");
        assertThat(message.getText()).isEqualTo("Gracias por tu compra.");
        verifyNoMoreInteractions(mailSender);
    }

    // UT-NOT-002
    @Test
    void sendEmail_shouldPropagateFailure_whenMailSenderFails() {
        var failure = new MailSendException("smtp unavailable");
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));

        assertThat(assertThrows(MailSendException.class, () ->
                service.sendEmail("buyer@example.test", "Subject", "Body"))).isSameAs(failure);

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        verifyNoMoreInteractions(mailSender);
    }
}
