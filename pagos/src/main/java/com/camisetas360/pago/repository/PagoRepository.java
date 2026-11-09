package com.camisetas360.pago.repository;

import com.camisetas360.pago.model.Pago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PagoRepository extends JpaRepository<Pago, UUID> {
    Optional<Pago> findByTokenTransaccion(String tokenTransaccion);
    List<Pago> findByOrdenIdOrderByCreatedAtDesc(UUID ordenId);
}
