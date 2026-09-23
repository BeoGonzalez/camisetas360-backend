package com.camisetas360.notifications.controller;

import com.camisetas360.notifications.dto.SendEmailRequest;
import com.camisetas360.notifications.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final EmailService emailService;

    public NotificationController(
            EmailService emailService
    ) {
        this.emailService = emailService;
    }

    @PostMapping("/email")
    public ResponseEntity<Map<String, String>> sendEmail(
            @Valid @RequestBody SendEmailRequest request
    ) {

        emailService.sendEmail(
                request.to(),
                request.subject(),
                request.body()
        );

        return ResponseEntity.ok(
                Map.of(
                        "status", "SENT",
                        "to", request.to()
                )
        );
    }
}