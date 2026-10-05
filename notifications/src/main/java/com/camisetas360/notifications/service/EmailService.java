package com.camisetas360.notifications.service;

import com.camisetas360.notifications.model.EmailDelivery;
import com.camisetas360.notifications.repository.EmailDeliveryRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String from;
    private final EmailDeliveryRepository deliveries;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String from,
            EmailDeliveryRepository deliveries
    ) {
        this.mailSender = mailSender;
        this.from = from;
        this.deliveries = deliveries;
    }

    @Transactional
    public void sendEmail(
            String to,
            String subject,
            String body
    ) {

        SimpleMailMessage message =
                new SimpleMailMessage();

        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        var delivery = new EmailDelivery(from, to, subject, body);
        // Validate persistence before SMTP. SMTP exceptions roll back the log;
        // SMTP and the database are not an atomic distributed transaction.
        deliveries.saveAndFlush(delivery);
        mailSender.send(message);
        delivery.markSent();
    }
}
