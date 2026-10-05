package com.camisetas360.carrito.repository;

import com.camisetas360.carrito.model.CheckoutRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutRequestRepository extends JpaRepository<CheckoutRequest, UUID> {
    @Override @EntityGraph(attributePaths = "items")
    Optional<CheckoutRequest> findById(UUID id);
}
