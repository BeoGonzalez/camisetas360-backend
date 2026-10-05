package com.camisetas360.notifications.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "email_deliveries")
public class EmailDelivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, columnDefinition = "text") private String sender;
    @Column(nullable = false, columnDefinition = "text") private String recipient;
    @Column(nullable = false, columnDefinition = "text") private String subject;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Column(name = "sent_at", nullable = false) private Instant sentAt;

    protected EmailDelivery() { }
    public EmailDelivery(String sender, String recipient, String subject, String body) {
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        sentAt = Instant.now();
    }
    public void markSent() { sentAt = Instant.now(); }
    public Long getId() { return id; }
    public String getSender() { return sender; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Instant getSentAt() { return sentAt; }
}
