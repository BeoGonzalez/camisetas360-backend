package com.camisetas360.carrito.service;

import com.camisetas360.carrito.dtos.CheckoutResponseDTO;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.messaging.CheckoutEventPublisher;
import com.camisetas360.carrito.messaging.event.CheckoutItemEvent;
import com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CartService {

        private final CheckoutEventPublisher checkoutEventPublisher;

        public CartService(CheckoutEventPublisher checkoutEventPublisher) {
                this.checkoutEventPublisher = checkoutEventPublisher;
        }

        public CheckoutResponseDTO createOrder(OrderRequestDTO request) {

                Jwt jwt = (Jwt) SecurityContextHolder
                                .getContext()
                                .getAuthentication()
                                .getPrincipal();

                String userEmail = jwt.getClaimAsString("preferred_username");

                if (userEmail == null || userEmail.isBlank()) {
                        throw new IllegalStateException(
                                        "El token JWT no contiene el claim preferred_username");
                }

                List<CheckoutItemEvent> items = request.items()
                                .stream()
                                .map(item -> new CheckoutItemEvent(
                                                item.sku(),
                                                item.quantity(),
                                                item.unitPrice()))
                                .toList();

                CheckoutRequestedEvent event = new CheckoutRequestedEvent(
                                UUID.randomUUID(),
                                userEmail,
                                items,
                                Instant.now());

                checkoutEventPublisher.publishCheckoutRequested(event);

                double total = items.stream()
                                .mapToDouble(item -> item.unitPrice() * item.quantity())
                                .sum();

                return new CheckoutResponseDTO(
                                event.eventId(),
                                event.userEmail(),
                                total,
                                "PROCESSING");
        }
}