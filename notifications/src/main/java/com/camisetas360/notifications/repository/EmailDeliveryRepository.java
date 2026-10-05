package com.camisetas360.notifications.repository;

import com.camisetas360.notifications.model.EmailDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, Long> {
    List<EmailDelivery> findByRecipientOrderBySentAtDesc(String recipient);
}
