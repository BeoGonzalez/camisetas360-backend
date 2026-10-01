package com.camisetas360.notifications.controller;

import com.camisetas360.notifications.dto.SendEmailRequest;
import com.camisetas360.notifications.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private EmailService service;
    @InjectMocks
    private NotificationController controller;

    // UT-NOT-005
    @Test
    void sendEmail_shouldPropagateFailure_whenEmailServiceFails() {
        var request = new SendEmailRequest("buyer@example.test", "Subject", "Body");
        var failure = new MailSendException("smtp unavailable");
        doThrow(failure).when(service).sendEmail("buyer@example.test", "Subject", "Body");

        assertThat(assertThrows(MailSendException.class,
                () -> controller.sendEmail(request))).isSameAs(failure);

        verify(service, times(1)).sendEmail("buyer@example.test", "Subject", "Body");
        verifyNoMoreInteractions(service);
    }
}
