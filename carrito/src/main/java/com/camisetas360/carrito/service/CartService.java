package com.camisetas360.carrito.service;

import com.camisetas360.carrito.dtos.CheckoutResponseDTO;
import com.camisetas360.carrito.dtos.OrderItemDTO;
import com.camisetas360.carrito.dtos.OrderRequestDTO;
import com.camisetas360.carrito.messaging.CheckoutEventPublisher;
import com.camisetas360.carrito.messaging.event.CheckoutItemEvent;
import com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent;
import com.camisetas360.carrito.model.CheckoutRequest;
import com.camisetas360.carrito.repository.CheckoutRequestRepository;
import com.camisetas360.carrito.security.EntraIdentity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CartService {

        private final CheckoutEventPublisher checkoutEventPublisher;
        private final CheckoutRequestRepository checkouts;
        private final CatalogoProductos catalogo;
        private final TransactionOperations transaction;

        public CartService(CheckoutEventPublisher checkoutEventPublisher, CheckoutRequestRepository checkouts,
                           CatalogoProductos catalogo, TransactionOperations transaction) {
                this.checkoutEventPublisher = checkoutEventPublisher;
                this.checkouts = checkouts;
                this.catalogo = catalogo;
                this.transaction = transaction;
        }

        public CheckoutResponseDTO createOrder(OrderRequestDTO request) {

                Jwt jwt = (Jwt) SecurityContextHolder
                                .getContext()
                                .getAuthentication()
                                .getPrincipal();

                var identity = EntraIdentity.from(jwt);
                String userEmail = jwt.getClaimAsString("email");
                if (userEmail == null || userEmail.isBlank()) userEmail = jwt.getClaimAsString("preferred_username");
                if (userEmail == null || userEmail.chars().anyMatch(Character::isWhitespace)
                        || !userEmail.matches("[^@]+@[^@]+[.][^@]+")) {
                        throw new MissingContactEmailException();
                }

                // Precios y existencia vienen del catalogo, nunca del cliente. Se consulta
                // fuera de la transaccion para no retener una conexion de BD durante la llamada HTTP.
                List<String> skus = request.items().stream().map(OrderItemDTO::sku).distinct().toList();
                Map<String, ProductoCatalogo> productos = catalogo.buscar(skus);
                List<String> faltantes = skus.stream().filter(sku -> !productos.containsKey(sku)).toList();
                if (!faltantes.isEmpty()) {
                        throw new ProductoNoDisponibleException(faltantes);
                }

                List<CheckoutItemEvent> items = request.items()
                                .stream()
                                .map(item -> new CheckoutItemEvent(
                                                item.sku(),
                                                item.quantity(),
                                                productos.get(item.sku()).precio()))
                                .toList();

                CheckoutRequestedEvent event = new CheckoutRequestedEvent(
                                UUID.randomUUID(),
                                userEmail,
                                items,
                                Instant.now(), identity.tenantId(), identity.objectId());

                double total = Math.round(items.stream()
                                .mapToDouble(item -> item.unitPrice() * item.quantity())
                                .sum() * 100.0) / 100.0;

                // Flush real constraints before publishing. A broker exception rolls
                // this database transaction back; this is not a distributed outbox.
                transaction.executeWithoutResult(status -> {
                        checkouts.saveAndFlush(new CheckoutRequest(event, total));
                        checkoutEventPublisher.publishCheckoutRequested(event);
                });

                return new CheckoutResponseDTO(
                                event.eventId(),
                                event.userEmail(),
                                total,
                                "PROCESSING");
        }
}
