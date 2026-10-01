package com.camisetas360.notifications.listener;

import com.camisetas360.notifications.event.OrderCreatedEvent;
import com.camisetas360.notifications.event.OrderItemEvent;
import com.camisetas360.notifications.service.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@ResourceLock(Resources.LOCALE)
class OrderCreatedListenerTest {

    @Mock
    private EmailService emailService;
    @InjectMocks
    private OrderCreatedListener listener;
    private Locale previousFormatLocale;

    @BeforeEach
    void usePredictableNumberFormat() {
        previousFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
    }

    @AfterEach
    void restoreNumberFormat() {
        Locale.setDefault(Locale.Category.FORMAT, previousFormatLocale);
    }

    // UT-NOT-003
    @Test
    void handleOrderCreated_shouldSendExpectedEmail_whenEventIsValid() {
        listener.handleOrderCreated(event());

        String expectedBody = """
                Hola,

                Tu orden fue creada correctamente.

                Número de orden: 42
                Total: $69.00
                Estado: CREATED

                Gracias por comprar en Camisetas360.
                """;
        verify(emailService, times(1)).sendEmail(
                "buyer@example.test", "Orden creada #42", expectedBody);
        verifyNoMoreInteractions(emailService);
    }

    // UT-NOT-004
    @Test
    void handleOrderCreated_shouldPropagateFailure_whenEmailServiceFails() {
        var failure = new MailSendException("smtp unavailable");
        doThrow(failure).when(emailService).sendEmail(anyString(), anyString(), anyString());

        assertThat(assertThrows(MailSendException.class,
                () -> listener.handleOrderCreated(event()))).isSameAs(failure);

        verify(emailService, times(1)).sendEmail(
                eq("buyer@example.test"), eq("Orden creada #42"), anyString());
        verifyNoMoreInteractions(emailService);
    }

    private static OrderCreatedEvent event() {
        return new OrderCreatedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                42L, "buyer@example.test", 69.0,
                List.of(new OrderItemEvent("SKU-A", 2, 19.5),
                        new OrderItemEvent("SKU-B", 3, 10.0)),
                Instant.parse("2026-01-01T12:00:00Z"));
    }
}
